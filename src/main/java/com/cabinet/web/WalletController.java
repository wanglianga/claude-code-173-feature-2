package com.cabinet.web;

import com.cabinet.domain.WalletAccount;
import com.cabinet.domain.WalletTxn;
import com.cabinet.service.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "账户余额", description = "用户余额与扣退款流水（幂等：同单不二次扣退）")
@RestController
@RequestMapping("/api/wallets")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;

    @Operation(summary = "用户余额")
    @GetMapping("/{userCode}")
    @PreAuthorize("isAuthenticated()")
    public WalletAccount account(@PathVariable String userCode) {
        return walletService.accountOf(userCode);
    }

    @Operation(summary = "用户余额流水（扣款/退款逐笔）")
    @GetMapping("/{userCode}/txns")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> txns(@PathVariable String userCode) {
        List<WalletTxn> txns = walletService.txnsOf(userCode);
        return Map.of("userCode", userCode, "txns", txns);
    }
}
