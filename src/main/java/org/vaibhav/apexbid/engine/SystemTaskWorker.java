package org.vaibhav.apexbid.engine;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.vaibhav.apexbid.config.RedisKeysConfig;
import org.vaibhav.apexbid.service.AuctionSettlementService;
import org.vaibhav.apexbid.service.CheckOutService;
import org.vaibhav.apexbid.service.DlqService;
import org.vaibhav.apexbid.service.WalletService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class SystemTaskWorker {

    private final AuctionSettlementService settlementService;
    private final CheckOutService checkoutService;
    private final WalletService walletService;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedisScript<String> fetchFromQueueScript;
    private final RedisKeysConfig redisKeys;
    private final DlqService dlqService;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService scheduler;
    private volatile boolean running = true;

    public SystemTaskWorker(AuctionSettlementService settlementService,
                            CheckOutService checkoutService,
                            WalletService walletService,
                            StringRedisTemplate stringRedisTemplate,
                            RedisScript<String> fetchFromQueueScript,
                            RedisKeysConfig redisKeys,
                            DlqService dlqService,
                            ObjectMapper objectMapper) {
        this.settlementService = settlementService;
        this.checkoutService = checkoutService;
        this.walletService = walletService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.fetchFromQueueScript = fetchFromQueueScript;
        this.redisKeys = redisKeys;
        this.dlqService = dlqService;
        this.objectMapper = objectMapper;
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @PostConstruct
    public void startWorker() {
        scheduler.execute(this::pollSystemTaskQueue);
        log.info("System Task Worker started (Unified Queue)");
    }

    @PreDestroy
    public void stopWorker() {
        this.running = false;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            log.info("System Task Worker safely shut down.");
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void pollSystemTaskQueue() {
        if (!running) return;

        String payloadStr = null;
        try {
            long currentTime = System.currentTimeMillis();
            long visibilityTimeoutScore = currentTime + 60000;

            payloadStr = stringRedisTemplate.execute(
                    fetchFromQueueScript,
                    List.of(redisKeys.getSystemTaskQueue(), redisKeys.getSystemTaskProcessingZset()),
                    String.valueOf(currentTime),          // ARGV[1]
                    String.valueOf(visibilityTimeoutScore) // ARGV[2]
            );

            if (payloadStr == null) {
                scheduler.schedule(this::pollSystemTaskQueue, 1, TimeUnit.SECONDS);
                return;
            }

            JsonNode payload = objectMapper.readTree(payloadStr);
            String type = payload.has("type") ? payload.get("type").asString() : "UNKNOWN";

            boolean dbSuccess;
            switch (type) {
                case "SETTLEMENT":
                    Long settlementAuctionId = payload.get("auctionId").asLong();
                    log.info("Processing SETTLEMENT for auctionId: {}", settlementAuctionId);
                    Map<Object, Object> redisData = stringRedisTemplate.opsForHash().entries(redisKeys.getAuctionHashPrefix() + settlementAuctionId);
                    dbSuccess = settlementService.commitToDatabase(settlementAuctionId, redisData);
                    if (dbSuccess) {
                        settlementService.executeRedisCleanup(settlementAuctionId);
                    }
                    break;

                case "CHECKOUT":
                    Long checkoutAuctionId = payload.get("auctionId").asLong();
                    log.info("Processing CHECKOUT for auctionId: {}", checkoutAuctionId);
                    dbSuccess = checkoutService.commitCheckoutToDatabase(checkoutAuctionId);
                    break;

                case "WITHDRAWAL":
                    Long userId = payload.get("userId").asLong();
                    long amount = payload.get("amount").asLong();
                    String requestId = payload.get("requestId").asString();
                    log.info("Processing WITHDRAWAL for requestId: {}", requestId);
                    dbSuccess = walletService.commitWithdrawalToDatabase(userId, amount, requestId);
                    break;

                default:
                    log.error("Unknown task type: {}", type);
                    dbSuccess = true; // Drop unknown tasks
            }

            if (dbSuccess) {
                stringRedisTemplate.opsForZSet().remove(redisKeys.getSystemTaskProcessingZset(), payloadStr);
            }

            scheduler.execute(this::pollSystemTaskQueue);

        } catch (Exception e) {
            log.error("Fatal error processing task. Payload: {}", payloadStr, e);
            if (payloadStr != null) {
                handleFailure(payloadStr, e);
                stringRedisTemplate.opsForZSet().remove(redisKeys.getSystemTaskProcessingZset(), payloadStr);
            }
            scheduler.schedule(this::pollSystemTaskQueue, 2, TimeUnit.SECONDS);
        }
    }

    private void handleFailure(String payloadStr, Exception e) {
        try {
            JsonNode payload = objectMapper.readTree(payloadStr);
            String type = payload.has("type") ? payload.get("type").asString() : "UNKNOWN";

            if ("WITHDRAWAL".equals(type)) {
                String requestId = payload.has("requestId") ? payload.get("requestId").asString() : "UNKNOWN_" + System.currentTimeMillis();
                dlqService.logWalletWorkerFailure(requestId, type, e);
            } else {
                Long auctionId = payload.has("auctionId") ? payload.get("auctionId").asLong() : -1L;
                dlqService.logFailure(auctionId, type, e);
            }
        } catch (Exception parseException) {
            log.error("Could not parse payload for DLQ logging", parseException);
            dlqService.logWalletWorkerFailure("UNPARSEABLE_" + System.currentTimeMillis(), "UNKNOWN", e);
        }
    }
}
