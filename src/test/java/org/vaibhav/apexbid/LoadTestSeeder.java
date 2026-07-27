package org.vaibhav.apexbid;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.vaibhav.apexbid.config.RedisKeysConfig;
import org.vaibhav.apexbid.entity.Auction;
import org.vaibhav.apexbid.entity.Product;
import org.vaibhav.apexbid.entity.User;
import org.vaibhav.apexbid.enums.AuctionStatus;
import org.vaibhav.apexbid.enums.AuctionType;
import org.vaibhav.apexbid.repository.AuctionRepository;
import org.vaibhav.apexbid.repository.ProductRepository;
import org.vaibhav.apexbid.repository.UserRepository;
import org.vaibhav.apexbid.security.JwtService;
import org.vaibhav.apexbid.service.AuctionStateTransitionService;
import org.vaibhav.apexbid.service.WalletService;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@SpringBootTest(properties = {
    "JWT_SECRET=fqLyNKs25Wae8P5m8IqtQfpeggAK5QIx8X6sB2kriAQ=",
    "JWT_EXPIRATION_MS=86400000",
    "PRODUCT_SECRET_ENCRYPTION_KEY=BdSpGntFsXeGn4KshbusjR4+Bu0LfV/ihpyB8rmrPss="
})
public class LoadTestSeeder {

    @Autowired private UserRepository userRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private AuctionRepository auctionRepository;
    @Autowired private WalletService walletService;
    @Autowired private JwtService jwtService;
    @Autowired private AuctionStateTransitionService transitionService;
    @Autowired private StringRedisTemplate stringRedisTemplate;
    @Autowired private RedisKeysConfig redisKeys;

    @Test
    void seedK6Data() {
        System.out.println("Seeding Database for k6 Load Test...");

        // 1. Create Seller
        User seller = new User();
        seller.setUsername("k6_seller_" + System.currentTimeMillis());
        seller.setEmail("k6_seller_" + System.currentTimeMillis() + "@test.com");
        seller.setPasswordHash("hash");
        seller = userRepository.save(seller);
        walletService.initializeNewWallet(seller);

        // 2. Create 2000 Virtual Users (Bidders)
        List<String> jwts = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            User bidder = new User();
            bidder.setUsername("k6_bidder_" + i + "_" + System.currentTimeMillis());
            bidder.setEmail("k6_bidder_" + i + "_" + System.currentTimeMillis() + "@test.com");
            bidder.setPasswordHash("hash");
            bidder = userRepository.save(bidder);
            
            // Fund with $1,000,000 each (100,000,000 cents)
            walletService.initializeNewWallet(bidder);
            walletService.hydrateWalletToRedis(bidder.getId()); 
            
            // Inject $1 Million into Redis bypassing Postgres validations for speed
            stringRedisTemplate.opsForHash().put(redisKeys.getWalletHashPrefix() + bidder.getId(), "balance", "100000000");

            // Generate JWT for the load test script
            String token = jwtService.generateToken(bidder, false);
            jwts.add(token);
        }

        // 3. Create 10 Active Auctions
        List<Long> auctionIds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Product product = new Product();
            product.setName("k6 Hype Item " + i);
            product.setDescription("Description for Product " + i);
            product.setImageUrl("https://picsum.photos/200/300?random=" + i);
            product.setSecretCode("SECRET_K6_" + i);
            product.setSeller(seller);
            product = productRepository.save(product);

            Auction auction = new Auction();
            auction.setTitle("Massive Hype Auction " + i);
            auction.setAuctionType(i % 2 == 0 ? AuctionType.ANTI_SNIPE : AuctionType.STANDARD);
            auction.setSeller(seller);
            auction.setProduct(product);
            auction.setStartPrice(100L); // $1.00
            auction.setWinningBid(100L);
            auction.setStartTime(Instant.now().minusSeconds(10));
            // 50% end in 1 minute (within test), 50% end in 4 minutes (after test)
            int randomDurationSeconds = ThreadLocalRandom.current().nextInt(300, 3601);
            auction.setEndTime(Instant.now().plusSeconds(randomDurationSeconds));
            auction.setStatus(AuctionStatus.UPCOMING);
            auction = auctionRepository.save(auction);
            
            auctionIds.add(auction.getId());

            // Hydrate to Redis for activation
            stringRedisTemplate.opsForZSet().add(redisKeys.getAuctionsUpcoming(), String.valueOf(auction.getId()), auction.getStartTime().toEpochMilli());
            
            String auctionKey = redisKeys.getAuctionHashPrefix() + auction.getId();
            Map<String, String> auctionData = new HashMap<>();
            auctionData.put("id", String.valueOf(auction.getId()));
            auctionData.put("title", auction.getTitle());
            auctionData.put("auction_type", auction.getAuctionType().name());
            auctionData.put("status", "UPCOMING");
            auctionData.put("start_price", String.valueOf(auction.getStartPrice()));
            auctionData.put("product_id", String.valueOf(product.getId()));
            auctionData.put("seller_id", String.valueOf(seller.getId()));
            auctionData.put("seller_username", seller.getUsername());
            auctionData.put("start_time", String.valueOf(auction.getStartTime().toEpochMilli()));
            auctionData.put("end_time", String.valueOf(auction.getEndTime().toEpochMilli()));
            auctionData.put("winning_bid", String.valueOf(auction.getWinningBid()));
            auctionData.put("winner_id", "");
            auctionData.put("winner_username", "");

            stringRedisTemplate.opsForHash().putAll(auctionKey, auctionData);
        }

        // Activate them!
        transitionService.activateUpcomingAuctions();

        // 4. Output Data for k6
        Map<String, Object> output = new HashMap<>();
        output.put("jwts", jwts);
        output.put("auctionIds", auctionIds);

        ObjectMapper mapper = new ObjectMapper();
        mapper.writerWithDefaultPrettyPrinter().writeValue(new File("k6/k6-data.json"), output);

        System.out.println("Data Seeding Complete! Wrote 2000 JWTs and 10 Auction IDs to k6/k6-data.json.");
    }
}
