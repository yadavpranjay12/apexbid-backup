package org.vaibhav.apexbid.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.vaibhav.apexbid.config.RedisKeysConfig;
import org.vaibhav.apexbid.dto.AuctionRedis;
import org.vaibhav.apexbid.enums.AuctionStatus;
import org.vaibhav.apexbid.repository.AuctionRepository;
import org.vaibhav.apexbid.repository.TransactionRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class SystemInitializationService {

    private final StringRedisTemplate stringRedisTemplate;
    private final AuctionRepository auctionRepository;
    private final AuctionQueryService auctionQueryService;
    private final RedisKeysConfig redisKeys;
    private final TransactionRepository transactionRepository;

    public SystemInitializationService(StringRedisTemplate stringRedisTemplate,
                                       AuctionRepository auctionRepository,
                                       AuctionQueryService auctionQueryService,
                                       RedisKeysConfig redisKeys,
                                       TransactionRepository transactionRepository) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.auctionRepository = auctionRepository;
        this.auctionQueryService = auctionQueryService;
        this.redisKeys = redisKeys;
        this.transactionRepository = transactionRepository;
    }

    @org.springframework.transaction.annotation.Transactional
    public void initializeClusterData() {
        log.info("[INITIALIZATION] Cold Start: Failing stranded processing transactions...");
        int stranded = transactionRepository.markAllPendingAsFailed();
        if (stranded > 0) {
            log.warn("[INITIALIZATION] Found and failed {} stranded PROCESSING transactions.", stranded);
        }

        log.info("[INITIALIZATION] Cold Start: Fetching active and upcoming auctions...");

        List<AuctionRedis> targetAuctions = auctionRepository.findAuctionsByStatusAndStartTimeLessThanEqual(
                List.of(AuctionStatus.ACTIVE, AuctionStatus.UPCOMING),
                Instant.now().plusSeconds(3600)
        );

        for (AuctionRedis auction : targetAuctions) {
            String auctionIdString = auction.id().toString();
            String auctionHashKey = redisKeys.getAuctionHashPrefix() + auctionIdString;

            if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(auctionHashKey))) {
                Map<String, String> fields = auctionQueryService.mapDtoToHash(auction);
                stringRedisTemplate.opsForHash().putAll(auctionHashKey, fields);
            }

            if (AuctionStatus.UPCOMING == auction.status()) {
                long startTimeEpoch = auction.startTime().toEpochMilli();
                // Symmetrical update
                stringRedisTemplate.opsForZSet().add(redisKeys.getAuctionsUpcoming(), auctionIdString, startTimeEpoch);

            } else if (AuctionStatus.ACTIVE == auction.status()) {
                long endTimeEpoch = auction.endTime().toEpochMilli();
                // Symmetrical updates
                stringRedisTemplate.opsForZSet().add(redisKeys.getAuctionsActive(), auctionIdString, endTimeEpoch);
                stringRedisTemplate.opsForZSet().add(redisKeys.getAuctionsHighestBids(), auctionIdString, auction.startPrice());
                stringRedisTemplate.opsForZSet().add(redisKeys.getAuctionsMostActive(), auctionIdString, 0);
            }
        }

        log.info("[INITIALIZATION] Completed. Loaded {} auctions into Redis.", targetAuctions.size());
    }
}