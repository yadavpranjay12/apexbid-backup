package org.vaibhav.apexbid.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import org.vaibhav.apexbid.dto.AuctionRedis;
import org.vaibhav.apexbid.entity.Auction;
import org.vaibhav.apexbid.enums.AuctionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AuctionRepository extends JpaRepository<Auction, Long> {
    Optional<Auction> findByProductId(Long productId);

    List<Auction> findBySellerId(Long sellerId);

    List<Auction> findByWinnerId(Long winnerId);

    @Modifying
    @Transactional
    @Query("UPDATE Auction a SET a.status = 'ACTIVE' WHERE a.id IN :ids")
    void bulkActivateAuctions(@Param("ids") List<Long> ids);

    @Query("""
            SELECT new org.vaibhav.apexbid.dto.AuctionRedis(
                a.id, a.title, a.auctionType, a.status, a.startPrice, a.winningBid, a.product.id,
                s.id, s.username, w.id, w.username, a.startTime, a.endTime
            )
            FROM Auction a
            JOIN a.seller s
            LEFT JOIN a.winner w
            WHERE a.status IN :statuses
              AND (a.status = 'ACTIVE' OR a.startTime <= :startTime)
        """)
    List<AuctionRedis> findAuctionsByStatusAndStartTimeLessThanEqual(
            @Param("statuses") List<AuctionStatus> statuses,
            @Param("startTime") Instant startTime
    );

    @Query("""
            SELECT new org.vaibhav.apexbid.dto.AuctionRedis(
                a.id, a.title, a.auctionType, a.status, a.startPrice, a.winningBid, a.product.id,
                s.id, s.username, w.id, w.username, a.startTime, a.endTime
            )
            FROM Auction a
            JOIN a.seller s
            LEFT JOIN a.winner w
            WHERE a.status IN :statuses
              AND (a.status = 'ACTIVE' OR a.startTime <= :startTime)
            ORDER BY a.id ASC
        """)
    Slice<AuctionRedis> findAuctionsByStatusAndStartTimeLessThanEqual(
            @Param("statuses") List<AuctionStatus> statuses,
            @Param("startTime") Instant startTime,
            Pageable pageable
    );
}