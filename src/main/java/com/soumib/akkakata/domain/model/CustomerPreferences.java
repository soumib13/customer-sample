package com.soumib.akkakata.domain.model;

public record CustomerPreferences(
    String theme,
    String locale,
    boolean marketingOptIn
) {
    public static CustomerPreferences defaults() {
        return new CustomerPreferences("light", "en-US", false);
    }
}
