package org.vaibhav.apexbid.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.vaibhav.apexbid.entity.FailedSettlement;
import org.vaibhav.apexbid.repository.FailedSettlementRepository;

import java.io.PrintWriter;
import java.io.StringWriter;

@Slf4j
@Service
@RequiredArgsConstructor
public class DlqService {

    private final FailedSettlementRepository failedSettlementRepository;

    @Transactional
    public void logFailure(Long auctionId, String phase, Throwable exception) {
        failedSettlementRepository.save(FailedSettlement.builder()
                .auctionId(auctionId)
                .phase(phase)
                .errorMessage(extractStackTrace(exception))
                .build());

        log.error("CRITICAL: Auction {} failed during {}. Moved to DLQ.", auctionId, phase);
    }

    @Transactional
    public void logWalletWorkerFailure(String requestId, String phase, Throwable exception) {
        failedSettlementRepository.save(FailedSettlement.builder()
                .requestId(requestId)
                .phase(phase)
                .errorMessage(extractStackTrace(exception))
                .build());

        log.error("CRITICAL: Request {} failed during {}. Moved to DLQ.", requestId, phase);
    }

    private String extractStackTrace(Throwable exception) {
        if (exception == null) {
            return "No exception provided";
        }
        StringWriter sw = new StringWriter();
        exception.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}