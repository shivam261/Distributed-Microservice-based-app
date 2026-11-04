package com.example.demospring.service;

import com.example.demospring.entity.User;
import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

final class AccountPrincipal extends org.springframework.security.core.userdetails.User {
    private final String accountId;

    AccountPrincipal(User user) {
        super(user.getUsername(), user.getPassword(), user.getRoles() == null ? List.of()
                : user.getRoles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList());
        this.accountId = user.getId();
    }

    String accountId() {
        return accountId;
    }
}
