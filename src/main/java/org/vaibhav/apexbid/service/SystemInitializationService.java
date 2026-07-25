package org.vaibhav.apexbid.service;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    @Transactional
    public void initializeClusterData() {
        log.info("[INITIALIZATION] Cold Start: Failing stranded processing transactions...");
        int stranded = transactionRepository.markAllPendingAsFailed();
        if (stranded > 0) {
            log.warn("[INITIALIZATION] Found and failed {} stranded PROCESSING transactions.", stranded);
        }

        log.info("[INITIALIZATION] Cold Start: Fetching active and upcoming auctions...");

        Pageable pageable = PageRequest.of(0, 1000);
        Slice<AuctionRedis> slice;
        int totalLoaded = 0;
        
        do {
            slice = auctionRepository.findAuctionsByStatusAndStartTimeLessThanEqual(
                    List.of(AuctionStatus.ACTIVE, AuctionStatus.UPCOMING),
                    Instant.now().plusSeconds(3600),
                    pageable
            );

            // Execute pipeline per batch of 1000
            if (slice.hasContent()) {
                final List<AuctionRedis> batch = slice.getContent();
                stringRedisTemplate.executePipelined(new SessionCallback<>() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <K, V> Object execute(@NonNull RedisOperations<K, V> operations) {
                        RedisOperations<String, String> stringOps = (RedisOperations<String, String>) operations;
                        
                        for (AuctionRedis auction : batch) {
                            String auctionIdString = auction.id().toString();
                            String auctionHashKey = redisKeys.getAuctionHashPrefix() + auctionIdString;
                            
                            Map<String, String> fields = auctionQueryService.mapDtoToHash(auction);
                            stringOps.opsForHash().putAll(auctionHashKey, fields);

                            if (AuctionStatus.UPCOMING == auction.status()) {
                                long startTimeEpoch = auction.startTime().toEpochMilli();
                                stringOps.opsForZSet().add(redisKeys.getAuctionsUpcoming(), auctionIdString, startTimeEpoch);
                            } else if (AuctionStatus.ACTIVE == auction.status()) {
                                long endTimeEpoch = auction.endTime().toEpochMilli();
                                stringOps.opsForZSet().add(redisKeys.getAuctionsActive(), auctionIdString, endTimeEpoch);
                                stringOps.opsForZSet().add(redisKeys.getAuctionsHighestBids(), auctionIdString, auction.startPrice());
                                stringOps.opsForZSet().add(redisKeys.getAuctionsMostActive(), auctionIdString, 0);
                            }
                        }
                        return null;
                    }
                });
                totalLoaded += slice.getNumberOfElements();
            }
            pageable = slice.nextPageable();
        } while (slice.hasNext());

        log.info("[INITIALIZATION] Completed. Loaded {} auctions into Redis.", totalLoaded);
    }
}