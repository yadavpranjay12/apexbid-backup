package org.vaibhav.apexbid.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.vaibhav.apexbid.dto.WalletRequest;
import org.vaibhav.apexbid.security.AuthenticatedUser;
import org.vaibhav.apexbid.service.WalletService;

@RestController
@RequestMapping("/wallet")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;

    @PostMapping("/deposit")
    public ResponseEntity<?> deposit(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody WalletRequest request) {
        try {
            return ResponseEntity.ok(walletService.deposit(user.id(), request.getAmount(), request.getRequestId()));
        } catch (RuntimeException e) {
            if ("Duplicate deposit request".equals(e.getMessage())) {
                return ResponseEntity.ok(walletService.getWalletBalanceMap(user.id()));
            }
            throw e;
        }
    }

    @PostMapping("/withdraw")
    public ResponseEntity<?> withdraw(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody WalletRequest request) {
        return walletService.withdraw(user.id(), request.getAmount(), request.getRequestId());
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refreshWallet(
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ResponseEntity.ok(walletService.refreshWallet(user.id()));
    }
}
