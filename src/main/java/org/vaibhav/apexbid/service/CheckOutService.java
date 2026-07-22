package org.vaibhav.apexbid.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.vaibhav.apexbid.config.RedisKeysConfig;
import org.vaibhav.apexbid.entity.Auction;
import org.vaibhav.apexbid.entity.Product;
import org.vaibhav.apexbid.entity.Transaction;
import org.vaibhav.apexbid.entity.Wallet;
import org.vaibhav.apexbid.enums.AuctionStatus;
import org.vaibhav.apexbid.enums.TransactionStatus;
import org.vaibhav.apexbid.enums.TransactionType;
import org.vaibhav.apexbid.repository.AuctionRepository;
import org.vaibhav.apexbid.repository.ProductRepository;
import org.vaibhav.apexbid.repository.TransactionRepository;
import org.vaibhav.apexbid.repository.WalletRepository;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CheckOutService {

    private final AuctionRepository auctionRepository;
    private final WalletRepository walletRepository;
    private final ProductRepository productRepository;
    private final TransactionRepository transactionRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedisScript<Long> finalizeCheckOutScript;
    private final RedisKeysConfig redisKeys;
    private final WalletService walletService;

    // Lua Status Constants
    private static final long LUA_SUCCESS = 1L;
    private static final long LUA_BUYER_MISSING = -1L;
    private static final long LUA_SELLER_MISSING = -2L;
    private static final long LUA_INSUFFICIENT_FUNDS = -3L;

    public long getPendingBalance(Long auctionId, Long requestUserId) {
        Auction auction = auctionRepository.findById(auctionId)
                .orElseThrow(() -> new RuntimeException("Auction not found"));

        boolean isWinner = auction.getWinner().getId().equals(requestUserId);
        boolean isSeller = auction.getSeller().getId().equals(requestUserId);

        if (!isWinner && !isSeller) {
            throw new SecurityException("Only the buyer or seller can view this balance.");
        }

        if (auction.getStatus() == AuctionStatus.SETTLED) {
            return 0L;
        }

        Transaction escrowTx = transactionRepository.findByAuction_IdAndTransactionType(auctionId, TransactionType.ESCROW_TRANSFER)
                .orElseThrow(() -> new RuntimeException("Escrow missing"));

        return auction.getWinningBid() - escrowTx.getAmount();
    }

    public void initiateCheckout(Long auctionId, Long requestUserId) {
        Auction auction = auctionRepository.findById(auctionId)
                .orElseThrow(() -> new RuntimeException("Auction not found"));

        if (auction.getStatus() != AuctionStatus.ESCROW_SECURED) {
            throw new IllegalStateException("Auction is not ready for final payment.");
        }

        if (!auction.getWinner().getId().equals(requestUserId)) {
            throw new SecurityException("Only the winner can checkout.");
        }

        // Prevent double-checkout spam
        boolean isAlreadyProcessing = transactionRepository.existsByAuction_IdAndTransactionTypeAndStatusIn(
                auctionId,
                TransactionType.FINAL_PAYMENT,
                List.of(TransactionStatus.PROCESSING, TransactionStatus.COMPLETED)
        );

        if (isAlreadyProcessing) {
            throw new IllegalStateException("Checkout is already processing or completed.");
        }

        long remainingAmount = getPendingBalance(auctionId, requestUserId);
        Long sellerId = auction.getSeller().getId();

        Transaction pendingTx = transactionRepository.save(Transaction.builder()
                .sender(auction.getWinner())
                .receiver(auction.getSeller())
                .auction(auction)
                .amount(remainingAmount)
                .transactionType(TransactionType.FINAL_PAYMENT)
                .status(TransactionStatus.PROCESSING)
                .build());

        String winnerWalletKey = redisKeys.getWalletHashPrefix() + requestUserId;
        String sellerWalletKey = redisKeys.getWalletHashPrefix() + sellerId;

        try {
            int attempts = 0;
            Long result = null;

            while (attempts < 2) {
                result = stringRedisTemplate.execute(
                        finalizeCheckOutScript,
                        List.of(winnerWalletKey, sellerWalletKey, redisKeys.getSystemTaskQueue()),
                        String.valueOf(remainingAmount),
                        String.valueOf(requestUserId),
                        String.valueOf(sellerId),
                        String.valueOf(auctionId),
                        redisKeys.getChannelWalletUpdates()
                );

                if (result == null) {
                    throw new RuntimeException("Redis execution failed or returned null.");
                }

                if (result == LUA_SUCCESS || result == LUA_INSUFFICIENT_FUNDS) {
                    break;
                }

                if (result == LUA_BUYER_MISSING) {
                    log.warn("Buyer wallet missing in Redis. Attempting hydration.");
                    if (!walletService.hydrateWalletToRedis(requestUserId)) {
                        throw new RuntimeException("Buyer wallet does not exist in the system.");
                    }
                } else if (result == LUA_SELLER_MISSING) {
                    log.warn("Seller wallet missing in Redis. Attempting hydration.");
                    if (!walletService.hydrateWalletToRedis(sellerId)) {
                        throw new RuntimeException("Seller wallet does not exist in the system.");
                    }
                }
                attempts++;
            }

            if (result == LUA_BUYER_MISSING || result == LUA_SELLER_MISSING) {
                throw new RuntimeException("CRITICAL: Wallet cache hydration failed after retries.");
            }
            if (result == LUA_INSUFFICIENT_FUNDS) {
                throw new IllegalStateException("Insufficient funds for final payment.");
            }

            log.info("Redis checkout successful for auction {}. Added to processing queue.", auctionId);

        } catch (Exception e) {
            pendingTx.setStatus(TransactionStatus.FAILED);
            transactionRepository.save(pendingTx);
            throw e;
        }
    }

    @Transactional
    @Retryable(includes = {OptimisticLockingFailureException.class}, maxRetries = 2, delay = 200, multiplier = 2.0)
    public boolean commitCheckoutToDatabase(Long auctionId) {
        Auction auction = auctionRepository.findById(auctionId)
                .orElseThrow(() -> new RuntimeException("Auction not found"));

        if (auction.getStatus() == AuctionStatus.SETTLED) {
            return true;
        }

        Transaction escrowTx = transactionRepository.findByAuction_IdAndTransactionType(auctionId, TransactionType.ESCROW_TRANSFER)
                .orElseThrow(() -> new RuntimeException("Escrow missing"));

        long remainingAmount = auction.getWinningBid() - escrowTx.getAmount();

        Wallet winnerWallet = walletRepository.findById(auction.getWinner().getId())
                .orElseThrow(() -> new RuntimeException("Winner wallet not found"));
        Wallet sellerWallet = walletRepository.findById(auction.getSeller().getId())
                .orElseThrow(() -> new RuntimeException("Seller wallet not found"));

        winnerWallet.setBalance(winnerWallet.getBalance() - remainingAmount);
        sellerWallet.setBalance(sellerWallet.getBalance() + remainingAmount);
        walletRepository.saveAll(List.of(winnerWallet, sellerWallet));

        try {
            String winnerWalletKey = redisKeys.getWalletHashPrefix() + auction.getWinner().getId();
            String sellerWalletKey = redisKeys.getWalletHashPrefix() + auction.getSeller().getId();
            
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(winnerWalletKey))) {
                stringRedisTemplate.opsForHash().increment(winnerWalletKey, "locked", -remainingAmount);
            }
            // Seller gets money only after DB commit
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(sellerWalletKey))) {
                stringRedisTemplate.opsForHash().increment(sellerWalletKey, "balance", remainingAmount);
            }
            walletService.refreshWallet(auction.getWinner().getId());
            walletService.refreshWallet(auction.getSeller().getId());
        } catch (Exception e) {
            log.error("Failed to clear Redis lock for checkout, auctionId: {}", auctionId, e);
        }

        Optional<Transaction> pendingTxOpt = transactionRepository.findByAuction_IdAndTransactionTypeAndStatus(
                auctionId,
                TransactionType.FINAL_PAYMENT,
                TransactionStatus.PROCESSING
        );

        if (pendingTxOpt.isPresent()) {
            Transaction tx = pendingTxOpt.get();
            tx.setStatus(TransactionStatus.COMPLETED);
            transactionRepository.save(tx);
        } else {
            transactionRepository.save(Transaction.builder()
                    .sender(winnerWallet.getUser())
                    .receiver(sellerWallet.getUser())
                    .auction(auction)
                    .amount(remainingAmount)
                    .transactionType(TransactionType.FINAL_PAYMENT)
                    .status(TransactionStatus.COMPLETED)
                    .build());
        }

        Product product = auction.getProduct();
        product.setBuyer(auction.getWinner());
        productRepository.save(product);

        auction.setStatus(AuctionStatus.SETTLED);
        auctionRepository.save(auction);

        return true;
    }
}