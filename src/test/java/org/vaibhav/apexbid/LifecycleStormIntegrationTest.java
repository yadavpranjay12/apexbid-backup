package org.vaibhav.apexbid;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.vaibhav.apexbid.config.RedisKeysConfig;
import org.vaibhav.apexbid.service.AuctionStateTransitionService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
    "JWT_SECRET=fqLyNKs25Wae8P5m8IqtQfpeggAK5QIx8X6sB2kriAQ=",
    "JWT_EXPIRATION_MS=86400000",
    "PRODUCT_SECRET_ENCRYPTION_KEY=BdSpGntFsXeGn4KshbusjR4+Bu0LfV/ihpyB8rmrPss="
})
public class LifecycleStormIntegrationTest {

    @Autowired private AuctionStateTransitionService transitionService;
    @Autowired private StringRedisTemplate stringRedisTemplate;
    @Autowired private RedisKeysConfig redisKeys;

    private final int STORM_SIZE = 50000;
    private final List<String> testAuctionIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        // Cleanup all 50k keys using pipeline for speed
        stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            byte[] activeKey = redisKeys.getAuctionsActive().getBytes();
            byte[] highestKey = redisKeys.getAuctionsHighestBids().getBytes();
            byte[] activeCountKey = redisKeys.getAuctionsMostActive().getBytes();

            for (String id : testAuctionIds) {
                byte[] hashKey = (redisKeys.getAuctionHashPrefix() + id).getBytes();
                byte[] idBytes = id.getBytes();

                connection.keyCommands().del(hashKey);
                connection.zSetCommands().zRem(activeKey, idBytes);
                connection.zSetCommands().zRem(highestKey, idBytes);
                connection.zSetCommands().zRem(activeCountKey, idBytes);
            }
            return null;
        });
    }

    @Test
    void testCronPipelineStorm() {
        // 1. Initial State
        Long initialActive = stringRedisTemplate.opsForZSet().zCard(redisKeys.getAuctionsActive());
        if (initialActive == null) initialActive = 0L;

        System.out.println("Preparing 50,000 auctions in Redis...");
        long now = System.currentTimeMillis();

        // 2. Bulk insert 50k auctions directly into Redis to simulate the state right before the cron job
        stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            byte[] upcomingKey = redisKeys.getAuctionsUpcoming().getBytes();
            
            for (int i = 0; i < STORM_SIZE; i++) {
                String id = "999" + i;
                testAuctionIds.add(id);

                byte[] hashKey = (redisKeys.getAuctionHashPrefix() + id).getBytes();
                byte[] idBytes = id.getBytes();

                // Hash data
                connection.hashCommands().hSet(hashKey, "status".getBytes(), "UPCOMING".getBytes());
                connection.hashCommands().hSet(hashKey, "end_time".getBytes(), String.valueOf(now + 100000).getBytes());
                connection.hashCommands().hSet(hashKey, "start_price".getBytes(), "1000".getBytes());

                // Add to UPCOMING zset with a score that guarantees activation (score = now)
                connection.zSetCommands().zAdd(upcomingKey, now - 1000, idBytes);
            }
            return null;
        });

        System.out.println("Firing the Lifecycle Storm (activateUpcomingAuctions)!");
        
        long start = System.currentTimeMillis();
        
        // 3. The Execution (This will attempt to pipeline 50k Lua scripts and then update Postgres)
        // Note: We expect this might crash at the Postgres level due to JDBC IN clause limits!
        assertDoesNotThrow(() -> {
            transitionService.activateUpcomingAuctions();
        }, "The transition service should handle 50k auctions without crashing.");

        long duration = System.currentTimeMillis() - start;
        System.out.println("Storm processed in: " + duration + "ms");

        // 4. Validation
        Long activeCount = stringRedisTemplate.opsForZSet().zCard(redisKeys.getAuctionsActive());
        if (activeCount == null) activeCount = 0L;
        assertEquals(initialActive + STORM_SIZE, activeCount, "All 50,000 auctions should have moved to ACTIVE.");
    }
}
