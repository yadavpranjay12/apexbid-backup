package org.vaibhav.apexbid.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "redis.keys")
@Getter
@Setter
public class RedisKeysConfig {

    // Core / Infrastructure
    private String systemState;
    private String clusterLock;

    // Hash Prefixes
    private String auctionHashPrefix;
    private String walletHashPrefix;
    private String winningSetPrefix;

    // Core ZSETs
    private String auctionsUpcoming;
    private String auctionsActive;
    private String auctionsHighestBids;
    private String auctionsMostActive;

    // System Tasks
    private String systemTaskQueue;
    private String systemTaskProcessingZset;

    // Pub/Sub Channels
    private String channelAuctionUpdates;
    private String channelWalletUpdates;

}