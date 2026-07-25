package org.vaibhav.apexbid.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.vaibhav.apexbid.config.RedisKeysConfig;
import org.vaibhav.apexbid.entity.Transaction;
import org.vaibhav.apexbid.entity.User;
import org.vaibhav.apexbid.entity.Wallet;
import org.vaibhav.apexbid.enums.TransactionStatus;
import org.vaibhav.apexbid.enums.TransactionType;
import org.vaibhav.apexbid.repository.TransactionRepository;
import org.vaibhav.apexbid.repository.WalletRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class WalletService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final StringRedisTemplate redisTemplate;
    private final RedisKeysConfig redisKeys;
    private final RedisScript<Long> hydrateWalletScript;
    private final RedisScript<Long> withdrawFundsScript;
    private final RedisScript<List> refreshWalletScript;

    public WalletService(WalletRepository walletRepository,
                         TransactionRepository transactionRepository,
                         StringRedisTemplate redisTemplate,
                         RedisKeysConfig redisKeys,
                         RedisScript<Long> hydrateWalletScript,
                         RedisScript<Long> withdrawFundsScript,
                         RedisScript<List> refreshWalletScript) {
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
        this.redisTemplate = redisTemplate;
        this.redisKeys = redisKeys;
        this.hydrateWalletScript = hydrateWalletScript;
        this.withdrawFundsScript = withdrawFundsScript;
        this.refreshWalletScript = refreshWalletScript;
    }

    /**
     * Creates a fresh DB wallet record and attempts to populate Redis.
     * Note: Redis hydration is best-effort. The database remains the source of truth.
     */
    @Transactional
    public void initializeNewWallet(User user) {
        Wallet wallet = new Wallet();
        wallet.setUser(user);
        wallet.setBalance(1000000L); // Sign-up bonus: $10,000.00 in cents
        walletRepository.save(wallet);

        try {
            String redisKey = redisKeys.getWalletHashPrefix() + user.getId();
            redisTemplate.execute(hydrateWalletScript, List.of(redisKey),
                    String.valueOf(wallet.getBalance()), "0");
            log.info("[WALLET] Initialized and hydrated fresh wallet for userId: {}", user.getId());
        } catch (Exception e) {
            log.warn("[WALLET] DB wallet created for userId: {} but Redis hydration failed. It will be hydrated on demand later. Reason: {}", user.getId(), e.getMessage());
        }
    }

    /**
     * Safely attempts to hydrate a wallet to Redis in a thread-safe manner using Lua.
     * Returns true if successful, false if the wallet doesn't exist or Redis is unreachable.
     */
    public boolean hydrateWalletToRedis(Long userId) {
        Wallet wallet = walletRepository.findByUserId(userId);
        if (wallet == null) {
            return false;
        }

        try {
            String redisKey = redisKeys.getWalletHashPrefix() + userId;
            Long result = redisTemplate.execute(hydrateWalletScript, List.of(redisKey),
                    String.valueOf(wallet.getBalance()), "0");

            if (Long.valueOf(1).equals(result)) {
                log.info("[WALLET-HYDRATE] Successfully restored userId: {} wallet snapshot to Redis.", userId);
            }
            return true;
        } catch (Exception e) {
            log.error("[WALLET-HYDRATE] Redis operation failed while hydrating wallet for userId: {}. Reason: {}", userId, e.getMessage());
            return false;
        }
    }

    @Transactional
    public Map<String, Object> deposit(Long userId, long amount, String requestId) {
        if (transactionRepository.existsByRequestId(requestId)) {
            return getWalletBalanceMap(userId);
        }

        Wallet wallet = walletRepository.findByUserId(userId);
        if (wallet == null) throw new RuntimeException("Wallet not found");

        wallet.setBalance(wallet.getBalance() + amount);
        walletRepository.save(wallet);

        Transaction transaction = Transaction.builder()
                .receiver(wallet.getUser())
                .amount(amount)
                .transactionType(TransactionType.DEPOSIT)
                .requestId(requestId)
                .build();
        try {
            transactionRepository.saveAndFlush(transaction);
        } catch (DataIntegrityViolationException e) {
            throw new RuntimeException("Duplicate deposit request");
        }

        String redisKey = redisKeys.getWalletHashPrefix() + userId;
        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(redisKey))) {
                redisTemplate.opsForHash().increment(redisKey, "balance", amount);
                
                List<Object> values = redisTemplate.opsForHash().multiGet(redisKey, List.of("balance", "locked"));
                String newBalance = (String) values.get(0);
                String locked = (String) values.get(1);
                if (newBalance != null) {
                    String payload = String.format("{\"userId\":%d,\"balance\":\"%s\",\"locked\":\"%s\"}", userId, newBalance, locked == null ? "0" : locked);
                    redisTemplate.convertAndSend(redisKeys.getChannelWalletUpdates(), payload);
                }
            }
        } catch (Exception e) {
            log.warn("[WALLET-DEPOSIT] Redis update failed for deposit userId: {}. UI might show stale balance until refresh.", userId);
        }

        return getWalletBalanceMap(userId);
    }

    public ResponseEntity<?> withdraw(Long userId, long amount, String requestId) {
        if (transactionRepository.existsByRequestId(requestId)) {
            return ResponseEntity.accepted().body(Map.of("status", "Withdrawal processing"));
        }

        Wallet wallet = walletRepository.findByUserId(userId);
        if (wallet == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Wallet not found"));
        }

        Transaction pendingTx = Transaction.builder()
                .sender(wallet.getUser())
                .amount(amount)
                .transactionType(TransactionType.WITHDRAWAL)
                .status(org.vaibhav.apexbid.enums.TransactionStatus.PROCESSING)
                .requestId(requestId)
                .build();
        
        try {
            transactionRepository.saveAndFlush(pendingTx);
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.accepted().body(Map.of("status", "Withdrawal processing"));
        }

        String redisKey = redisKeys.getWalletHashPrefix() + userId;

        int attempts = 0;
        Long result = null;

        while (attempts < 2) {
            result = redisTemplate.execute(
                    withdrawFundsScript,
                    List.of(redisKey, redisKeys.getSystemTaskQueue()),
                    String.valueOf(amount),
                    String.valueOf(userId),
                    requestId,
                    redisKeys.getChannelWalletUpdates()
            );

            if (result == null) {
                pendingTx.setStatus(TransactionStatus.FAILED);
                transactionRepository.save(pendingTx);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Redis execution failed"));
            }

            if (result == 1L || result == -2L) break;

            if (result == -1L) {
                if (!hydrateWalletToRedis(userId)) {
                    pendingTx.setStatus(org.vaibhav.apexbid.enums.TransactionStatus.FAILED);
                    transactionRepository.save(pendingTx);
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Wallet not found"));
                }
            }
            attempts++;
        }

        if (result == -1L) {
            pendingTx.setStatus(org.vaibhav.apexbid.enums.TransactionStatus.FAILED);
            transactionRepository.save(pendingTx);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to load wallet"));
        }
        if (result == -2L) {
            pendingTx.setStatus(org.vaibhav.apexbid.enums.TransactionStatus.FAILED);
            transactionRepository.save(pendingTx);
            return ResponseEntity.badRequest().body(Map.of("error", "Insufficient available funds"));
        }

        return ResponseEntity.accepted().body(Map.of("status", "Withdrawal processing"));
    }

    @Transactional
    public boolean commitWithdrawalToDatabase(Long userId, long amount, String requestId) {
        Wallet wallet = walletRepository.findByUserId(userId);
        if (wallet == null) return false;

        Optional<Transaction> txOpt = transactionRepository.findByRequestId(requestId);
        if (txOpt.isPresent()) {
            Transaction tx = txOpt.get();
            if (tx.getStatus() == org.vaibhav.apexbid.enums.TransactionStatus.COMPLETED) {
                return true; // Already processed
            }
            tx.setStatus(org.vaibhav.apexbid.enums.TransactionStatus.COMPLETED);
            transactionRepository.save(tx);
        } else {
            // Fallback in case of manual queuing
            Transaction transaction = Transaction.builder()
                    .sender(wallet.getUser())
                    .amount(amount)
                    .transactionType(TransactionType.WITHDRAWAL)
                    .status(org.vaibhav.apexbid.enums.TransactionStatus.COMPLETED)
                    .requestId(requestId)
                    .build();
            transactionRepository.save(transaction);
        }

        wallet.setBalance(wallet.getBalance() - amount);
        walletRepository.save(wallet);
        
        try {
            String redisKey = redisKeys.getWalletHashPrefix() + userId;
            if (Boolean.TRUE.equals(redisTemplate.hasKey(redisKey))) {
                redisTemplate.opsForHash().increment(redisKey, "locked", -amount);
            }
            refreshWallet(userId);
        } catch (Exception e) {
            log.error("Failed to clear Redis lock for withdrawal, userId: {}", userId, e);
        }

        return true;
    }

    public Map<String, Object> refreshWallet(Long userId) {
        Wallet wallet = walletRepository.findByUserId(userId);
        if (wallet == null) throw new RuntimeException("Wallet not found");

        String redisKey = redisKeys.getWalletHashPrefix() + userId;
        long totalBalance = wallet.getBalance();
        
        long availableBalance = totalBalance;
        long locked = 0L;

        try {
            List<Object> result = redisTemplate.execute(
                refreshWalletScript, 
                List.of(redisKey), 
                String.valueOf(totalBalance)
            );
            
            if (result != null && result.size() == 2) {
                availableBalance = Long.parseLong(result.get(0).toString());
                locked = Long.parseLong(result.get(1).toString());
                
                String payload = String.format("{\"userId\":%d,\"balance\":\"%d\",\"locked\":\"%d\"}", userId, availableBalance, locked);
                redisTemplate.convertAndSend(redisKeys.getChannelWalletUpdates(), payload);
            }
        } catch (Exception e) {
            log.error("Failed to run refresh_wallet.lua for user {}: {}", userId, e.getMessage());
        }

        return Map.of(
            "available", availableBalance,
            "locked", locked,
            "total", totalBalance
        );
    }

    public Map<String, Object> getWalletBalanceMap(Long userId) {
        Wallet wallet = walletRepository.findByUserId(userId);
        if (wallet == null) return Map.of();
        
        String redisKey = redisKeys.getWalletHashPrefix() + userId;
        long locked = 0L;
        try {
            String lockedStr = (String) redisTemplate.opsForHash().get(redisKey, "locked");
            locked = lockedStr != null ? Long.parseLong(lockedStr) : 0L;
        } catch (Exception e) {
            log.warn("Could not fetch locked balance from Redis for user {}. Assuming 0 to allow DB commit.", userId);
        }
        
        return Map.of(
            "available", wallet.getBalance() - locked,
            "locked", locked,
            "total", wallet.getBalance()
        );
    }
}