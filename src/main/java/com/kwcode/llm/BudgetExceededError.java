package com.kwcode.llm;

/**
 * Token预算超限异常
 * @origin kaiwu/llm/llama_backend.py::BudgetExceededError
 */
public class BudgetExceededError extends RuntimeException {
    public BudgetExceededError(String message) { super(message); }
}
