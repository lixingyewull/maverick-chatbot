package com.maverick.maverickchatbot.web;

import com.maverick.maverickchatbot.ai.roles.RoleConfig;
import com.maverick.maverickchatbot.ai.roles.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @GetMapping
    public List<RoleConfig> list() {
        return roleService.listRoles();
    }
}


