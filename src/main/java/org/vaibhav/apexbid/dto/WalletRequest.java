package org.vaibhav.apexbid.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class WalletRequest {
    @Min(value = 1, message = "Amount must be at least 1 cent")
    private long amount;

    @NotBlank(message = "requestId is required")
    private String requestId;
}
