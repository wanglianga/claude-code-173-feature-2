package com.cabinet.web;

import com.cabinet.domain.AccountTxn;
import com.cabinet.domain.MemberAccount;
import com.cabinet.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;
import java.util.Map;

/** 会员账户：余额与扣款/退款流水查询，误扣款退款后可在此核对回补 */
@Tag(name = "会员账户", description = "账户余额查询、扣款/退款回补流水、测试充值")
@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    public record RechargeRequest(BigDecimal amount, String remark) {}

    @Operation(summary = "按用户号查询余额与账户")
    @GetMapping("/{userCode}")
    @PreAuthorize("hasAnyRole('CUSTOMER_SERVICE','OPERATOR','ADMIN','FINANCE')")
    public MemberAccount account(@PathVariable String userCode) {
        return accountService.get(userCode);
    }

    @Operation(summary = "按用户号查询扣款/退款流水")
    @GetMapping("/{userCode}/txns")
    @PreAuthorize("hasAnyRole('CUSTOMER_SERVICE','OPERATOR','ADMIN','FINANCE')")
    public List<AccountTxn> txns(@PathVariable String userCode) {
        return accountService.txns(userCode);
    }

    @Operation(summary = "测试用：给会员账户充值")
    @PostMapping("/{userCode}/recharge")
    @PreAuthorize("hasAnyRole('CUSTOMER_SERVICE','ADMIN')")
    public MemberAccount recharge(@PathVariable String userCode, @RequestBody RechargeRequest req,
                                  Principal principal) {
        return accountService.recharge(userCode, principal.getName(),
                req.amount(), req.remark());
    }

    @Operation(summary = "当前登录账号信息（占位，配合 HTTP Basic 验证）")
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public Map<String, String> me(Principal principal) {
        return Map.of("username", principal.getName());
    }
}
