package com.maverick.maverickchatbot.ai.roles;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class RoleService {

    // 使用 LinkedHashMap 保持插入顺序（即 YAML 中的顺序）
    private final Map<String, RoleConfig> cache = new LinkedHashMap<>();

    public List<RoleConfig> listRoles() {
        ensureLoaded();
        // 按插入顺序返回副本，避免外部修改
        return new ArrayList<>(cache.values());
    }

    public RoleConfig getById(String id) {
        ensureLoaded();
        return cache.get(id);
    }

    private synchronized void ensureLoaded() {
        if (!cache.isEmpty()) return;
        try {
            ClassPathResource res = new ClassPathResource("roles/roles.yaml");
            try (InputStream in = res.getInputStream()) {
                ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
                RoleConfig[] arr = mapper.readValue(in, RoleConfig[].class);
                Arrays.stream(arr).forEach(rc -> cache.put(rc.getId(), rc));
                log.info("Loaded roles: {}", cache.keySet());
            }
        } catch (Exception e) {
            log.warn("Load roles failed: {}", e.getMessage());
        }
    }
}


