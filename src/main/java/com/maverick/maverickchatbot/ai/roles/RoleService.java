package com.maverick.maverickchatbot.ai.roles;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class RoleService {

    private final Map<String, RoleConfig> cache = new ConcurrentHashMap<>();

    public List<RoleConfig> listRoles() {
        ensureLoaded();
        return cache.values().stream().toList();
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


