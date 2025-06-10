package com.krickert.search.engine.registration;

import com.krickert.yappy.registration.api.RegisterModuleRequest;
import reactor.core.publisher.Mono;

/**
 * Interface for validating module registration requests.
 */
public interface ModuleRegistrationValidator {
    
    /**
     * Validates a module registration request.
     * 
     * @param request the registration request to validate
     * @return a Mono containing the validation result
     */
    Mono<ValidationResult> validate(RegisterModuleRequest request);
    
    /**
     * Result of module registration validation.
     */
    record ValidationResult(
            boolean isValid,
            String errorMessage,
            ValidationLevel level
    ) {
        public static ValidationResult valid() {
            return new ValidationResult(true, null, ValidationLevel.INFO);
        }
        
        public static ValidationResult error(String message) {
            return new ValidationResult(false, message, ValidationLevel.ERROR);
        }
        
        public static ValidationResult warning(String message) {
            return new ValidationResult(true, message, ValidationLevel.WARNING);
        }
    }
    
    /**
     * Validation severity level.
     */
    enum ValidationLevel {
        INFO,
        WARNING,
        ERROR
    }
}