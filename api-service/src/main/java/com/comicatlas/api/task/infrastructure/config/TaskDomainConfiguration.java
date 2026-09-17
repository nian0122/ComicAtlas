package com.comicatlas.api.task.infrastructure.config;

import com.comicatlas.api.task.domain.policy.OperationPolicyService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 管理任务域的 Spring 装配；领域规则本身不依赖 Spring。 */
@Configuration
public class TaskDomainConfiguration {

    @Bean
    public OperationPolicyService operationPolicyService() {
        return new OperationPolicyService();
    }
}
