/*
 *    Copyright 2010-2026 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package com.jpetstore.order.exception;

/**
 * Exception thrown when a requested resource is not found.
 *
 * <p>Used across the Order Service to indicate that a specific entity (cart item,
 * cart, order) cannot be located by its identifier. This exception is mapped to
 * HTTP 404 Not Found by controller-level {@code @ExceptionHandler} methods.</p>
 *
 * <p>Replaces the generic {@code RuntimeException} throws in {@code CartStateService}
 * that previously resulted in HTTP 500 Internal Server Error responses.</p>
 *
 * @author Blitzy Platform
 */
public class ResourceNotFoundException extends RuntimeException {

    /**
     * Constructs a new ResourceNotFoundException with the specified detail message.
     *
     * @param message the detail message describing the missing resource
     */
    public ResourceNotFoundException(String message) {
        super(message);
    }

    /**
     * Constructs a new ResourceNotFoundException with the specified detail message
     * and cause.
     *
     * @param message the detail message describing the missing resource
     * @param cause   the cause of the exception
     */
    public ResourceNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
