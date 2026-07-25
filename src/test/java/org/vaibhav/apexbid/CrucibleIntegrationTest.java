package org.vaibhav.apexbid;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.vaibhav.apexbid.config.RedisKeysConfig;
import org.vaibhav.apexbid.controller.BidController;
import org.vaibhav.apexbid.dto.BidRequest;
import org.vaibhav.apexbid.entity.Auction;
import org.vaibhav.apexbid.entity.Product;
import org.vaibhav.apexbid.entity.User;
import org.vaibhav.apexbid.enums.AuctionStatus;
import org.vaibhav.apexbid.enums.AuctionType;
import org.vaibhav.apexbid.repository.AuctionRepository;
import org.vaibhav.apexbid.repository.ProductRepository;
import org.vaibhav.apexbid.repository.UserRepository;
import org.vaibhav.apexbid.security.AuthenticatedUser;
import org.vaibhav.apexbid.service.AuctionStateTransitionService;
import org.vaibhav.apexbid.service.WalletService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
    "JWT_SECRET=fqLyNKs25Wae8P5m8IqtQfpeggAK5QIx8X6sB2kriAQ=",
    "JWT_EXPIRATION_MS=86400000",
    "PRODUCT_SECRET_ENCRYPTION_KEY=BdSpGntFsXeGn4KshbusjR4+Bu0LfV/ihpyB8rmrPss="
})
public class CrucibleIntegrationTest {

    @Autowired private BidController bidController;
    @Autowired private UserRepository userRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private AuctionRepository auctionRepository;
    @Autowired private WalletService walletService;
    @Autowired private AuctionStateTransitionService transitionService;
    @Autowired private StringRedisTemplate stringRedisTemplate;
    @Autowired private RedisKeysConfig redisKeys;

    private User seller;
    private List<User> bidders = new ArrayList<>();
    private Auction testAuction;

    @BeforeEach
    void setup() {
        // 1. Create Seller
        seller = new User();
        seller.setUsername("crucible_seller_" + System.currentTimeMillis());
        seller.setEmail("seller_" + System.currentTimeMillis() + "@test.com");
        seller.setPasswordHash("hash");
        seller = userRepository.save(seller);
        walletService.initializeNewWallet(seller);

        // 2. Create 10 Bidders with $10,000 each (1,000,000 cents)
        for (int i = 0; i < 10; i++) {
            User bidder = new User();
            bidder.setUsername("crucible_bidder_" + i + "_" + System.currentTimeMillis());
            bidder.setEmail("bidder_" + i + "_" + System.currentTimeMillis() + "@test.com");
            bidder.setPasswordHash("hash");
            bidder = userRepository.save(bidder);
            walletService.initializeNewWallet(bidder);
            walletService.hydrateWalletToRedis(bidder.getId()); 
            bidders.add(bidder);
        }

        // 3. Create a Product
        Product product = new Product();
        product.setName("Crucible Item");
        product.setSecretCode("SECRET123");
        product.setSeller(seller);
        product = productRepository.save(product);

        // 4. Create an UPCOMING auction
        testAuction = new Auction();
        testAuction.setTitle("Crucible Test Item");
        testAuction.setAuctionType(AuctionType.ANTI_SNIPE);
        testAuction.setSeller(seller);
        testAuction.setProduct(product);
        testAuction.setStartPrice(1000L); // $10.00
        testAuction.setWinningBid(1000L);
        testAuction.setStartTime(Instant.now().minusSeconds(10));
        testAuction.setEndTime(Instant.now().plusSeconds(600)); // Ends in 10 mins
        testAuction.setStatus(AuctionStatus.UPCOMING);
        testAuction = auctionRepository.save(testAuction);

        // Add to Redis ZSET for activation
        stringRedisTemplate.opsForZSet().add(redisKeys.getAuctionsUpcoming(), String.valueOf(testAuction.getId()), testAuction.getStartTime().toEpochMilli());

        // Hydrate Auction Hash
        String auctionKey = redisKeys.getAuctionHashPrefix() + testAuction.getId();
        stringRedisTemplate.opsForHash().putAll(auctionKey, Map.of(
            "status", "UPCOMING",
            "end_time", String.valueOf(testAuction.getEndTime().toEpochMilli()),
            "start_price", String.valueOf(testAuction.getStartPrice()),
            "auction_type", "ANTI_SNIPE",
            "seller_id", String.valueOf(seller.getId())
        ));

        // Transition it to ACTIVE (Pushes to Redis)
        transitionService.activateUpcomingAuctions();
    }

    @AfterEach
    void cleanup() {
        if (testAuction != null) {
            // Force the auction to expire immediately so the Cron Job and Worker pick it up and naturally settle it in Postgres
            stringRedisTemplate.opsForHash().put(redisKeys.getAuctionHashPrefix() + testAuction.getId(), "end_time", "0");
            stringRedisTemplate.opsForZSet().add(redisKeys.getAuctionsActive(), String.valueOf(testAuction.getId()), 0);
        }
    }

    @Test
    void testOutbidRace_MathematicalProof() throws InterruptedException {
        int threadCount = 10;
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        List<ResponseEntity<?>> results = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        long bidAmount = 2000L; 

        for (int i = 0; i < threadCount; i++) {
            User bidder = bidders.get(i);
            AuthenticatedUser authUser = new AuthenticatedUser(bidder.getId(), bidder.getUsername(), "ROLE_USER");
            BidRequest request = new BidRequest(testAuction.getId(), bidAmount);

            executor.submit(() -> {
                try {
                    latch.await(); 
                    ResponseEntity<?> response = bidController.placeBid(authUser, request);
                    results.add(response);
                    if (response.getStatusCode() == HttpStatus.ACCEPTED) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    System.err.println("Thread error: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Release the hounds
        latch.countDown();
        doneLatch.await();

        assertEquals(1, successCount.get(), "Exactly one bid should succeed");
        assertEquals(threadCount - 1, failureCount.get(), "All other bids must fail");

        // Verify only 1 winner's escrow is locked, and previous winners got their money back.
        String auctionKey = redisKeys.getAuctionHashPrefix() + testAuction.getId();
        String winnerIdStr = (String) stringRedisTemplate.opsForHash().get(auctionKey, "winner_id");
        assertNotNull(winnerIdStr);

        for (User bidder : bidders) {
            Map<String, Object> walletInfo = walletService.refreshWallet(bidder.getId());
            long locked = Long.parseLong(walletInfo.get("locked").toString());
            long available = Long.parseLong(walletInfo.get("available").toString());
            if (String.valueOf(bidder.getId()).equals(winnerIdStr)) {
                assertEquals(100L, locked, "Winner should have escrow locked");
                assertEquals(1000000L - 100L, available, "Winner available balance should be reduced");
            } else {
                assertEquals(0L, locked, "Loser should have 0 locked");
                assertEquals(1000000L, available, "Loser should have full balance");
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void testBrokeBidder() {
        User brokeUser = new User();
        brokeUser.setUsername("broke_" + System.currentTimeMillis());
        brokeUser.setEmail("broke_" + System.currentTimeMillis() + "@test.com");
        brokeUser.setPasswordHash("hash");
        brokeUser = userRepository.save(brokeUser);
        walletService.initializeNewWallet(brokeUser);
        
        walletService.withdraw(brokeUser.getId(), 1000000L, "test-withdraw-" + System.currentTimeMillis());
        walletService.commitWithdrawalToDatabase(brokeUser.getId(), 1000000L, "test-withdraw");
        
        AuthenticatedUser authUser = new AuthenticatedUser(brokeUser.getId(), brokeUser.getUsername(), "ROLE_USER");
        BidRequest request = new BidRequest(testAuction.getId(), 5000L);
        ResponseEntity<?> response = bidController.placeBid(authUser, request);
        
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        Map<String, String> body = (Map<String, String>) response.getBody();
        assertEquals("Insufficient funds for escrow.", body.get("error"));
    }

    @Test
    void testSniper() {
        // Set auction to expire in 4 seconds
        long initialEnd = System.currentTimeMillis() + 4000;
        stringRedisTemplate.opsForHash().put(redisKeys.getAuctionHashPrefix() + testAuction.getId(), "end_time", String.valueOf(initialEnd));

        User bidder = bidders.get(0);
        AuthenticatedUser authUser = new AuthenticatedUser(bidder.getId(), bidder.getUsername(), "ROLE_USER");
        BidRequest request = new BidRequest(testAuction.getId(), 2500L);
        
        ResponseEntity<?> response = bidController.placeBid(authUser, request);
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());

        String newEndStr = (String) stringRedisTemplate.opsForHash().get(redisKeys.getAuctionHashPrefix() + testAuction.getId(), "end_time");
        long newEnd = Long.parseLong(newEndStr);
        
        assertTrue(newEnd > initialEnd, "Anti-snipe must extend the end time");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testLateComer() {
        long initialEnd = System.currentTimeMillis() - 1000;
        stringRedisTemplate.opsForHash().put(redisKeys.getAuctionHashPrefix() + testAuction.getId(), "end_time", String.valueOf(initialEnd));

        User bidder = bidders.get(0);
        AuthenticatedUser authUser = new AuthenticatedUser(bidder.getId(), bidder.getUsername(), "ROLE_USER");
        BidRequest request = new BidRequest(testAuction.getId(), 2500L);
        
        ResponseEntity<?> response = bidController.placeBid(authUser, request);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        Map<String, String> body = (Map<String, String>) response.getBody();
        assertEquals("Auction has ended.", body.get("error"));
    }
}
