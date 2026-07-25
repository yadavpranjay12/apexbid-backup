package org.vaibhav.apexbid.service;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.vaibhav.apexbid.config.RedisKeysConfig;
import org.vaibhav.apexbid.repository.AuctionRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class AuctionStateTransitionService {
    private final StringRedisTemplate stringRedisTemplate;
    private final AuctionRepository auctionRepository;
    private final DefaultRedisScript<Long> activateAuctionScript;
    private final DefaultRedisScript<Long> queueSettlementScript;
    private final RedisKeysConfig redisKeys;

    public AuctionStateTransitionService(StringRedisTemplate stringRedisTemplate,
                                         AuctionRepository auctionRepository,
                                         DefaultRedisScript<Long> activateAuctionScript,
                                         DefaultRedisScript<Long> queueSettlementScript,
                                         RedisKeysConfig redisKeys) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.auctionRepository = auctionRepository;
        this.activateAuctionScript = activateAuctionScript;
        this.queueSettlementScript = queueSettlementScript;
        this.redisKeys = redisKeys;
    }

    public void activateUpcomingAuctions() {
        long nowEpoch = Instant.now().toEpochMilli();
        Set<String> toActivate = stringRedisTemplate.opsForZSet().rangeByScore(redisKeys.getAuctionsUpcoming(), 0, nowEpoch);

        if (toActivate != null && !toActivate.isEmpty()) {
            List<String> listToActivate = new ArrayList<>(toActivate);
            int batchSize = 1000;

            for (int i = 0; i < listToActivate.size(); i += batchSize) {
                int end = Math.min(i + batchSize, listToActivate.size());
                List<String> batch = listToActivate.subList(i, end);

                // 1. Pipeline the Lua script executions in chunks
                List<Object> scriptResults = stringRedisTemplate.executePipelined(new SessionCallback<>() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <K, V> Object execute(@NonNull RedisOperations<K, V> operations) {
                        RedisOperations<String, String> stringOps = (RedisOperations<String, String>) operations;
                        for (String auctionId : batch) {
                            List<String> keys = List.of(
                                    redisKeys.getAuctionHashPrefix() + auctionId,
                                    redisKeys.getAuctionsUpcoming(),
                                    redisKeys.getAuctionsActive(),
                                    redisKeys.getAuctionsHighestBids(),
                                    redisKeys.getAuctionsMostActive()
                            );
                            stringOps.execute(activateAuctionScript, keys, auctionId, redisKeys.getChannelAuctionUpdates());
                        }
                        return null;
                    }
                });

                // 2. Process the pipeline results
                List<Long> auctionIdsToActivate = new ArrayList<>();
                int index = 0;
                for (String auctionId : batch) {
                    Long result = (Long) scriptResults.get(index++);
                    if (Long.valueOf(1).equals(result)) {
                        auctionIdsToActivate.add(Long.parseLong(auctionId));
                    }
                }

                // 3. Sync successful transitions to PostgreSQL
                if (!auctionIdsToActivate.isEmpty()) {
                    auctionRepository.bulkActivateAuctions(auctionIdsToActivate);
                    log.info("[LIFECYCLE] Synced {} auctions to ACTIVE in DB.", auctionIdsToActivate.size());
                }
            }
        }
    }

    public void queueExpiredAuctions() {
        long nowEpoch = Instant.now().toEpochMilli();
        Set<String> toSettle = stringRedisTemplate.opsForZSet().rangeByScore(redisKeys.getAuctionsActive(), 0, nowEpoch);

        if (toSettle != null && !toSettle.isEmpty()) {
            List<String> listToSettle = new ArrayList<>(toSettle);
            int batchSize = 1000;

            for (int i = 0; i < listToSettle.size(); i += batchSize) {
                int end = Math.min(i + batchSize, listToSettle.size());
                List<String> batch = listToSettle.subList(i, end);

                // 1. Pipeline execution in chunks
                List<Object> scriptResults = stringRedisTemplate.executePipelined(new SessionCallback<>() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <K, V> Object execute(@NonNull RedisOperations<K, V> operations) {
                        RedisOperations<String, String> stringOps = (RedisOperations<String, String>) operations;
                        for (String auctionId : batch) {
                            List<String> keys = List.of(
                                    redisKeys.getAuctionHashPrefix() + auctionId,
                                    redisKeys.getAuctionsActive(),
                                    redisKeys.getAuctionsHighestBids(),
                                    redisKeys.getAuctionsMostActive(),
                                    redisKeys.getSystemTaskQueue()
                            );
                            stringOps.execute(queueSettlementScript, keys, auctionId, redisKeys.getChannelAuctionUpdates());
                        }
                        return null;
                    }
                });

                // 2. Process results
                int index = 0;
                int count = 0;
                for (String auctionId : batch) {
                    Long result = (Long) scriptResults.get(index++);
                    if (Long.valueOf(1).equals(result)) {
                        count++;
                    }
                }
                if (count > 0) {
                    log.info("[LIFECYCLE] {} expired auctions committed to settlement queue.", count);
                }
            }
        }
    }
}