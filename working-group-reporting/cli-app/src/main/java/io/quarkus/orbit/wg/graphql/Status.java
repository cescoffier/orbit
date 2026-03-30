package io.quarkus.orbit.wg.graphql;

public enum Status {
        PAUSED("\uD83D\uDFE1"),
        ON_TRACK("\uD83D\uDFE2"),
        AT_RISK("\uD83D\uDFE0"),
        OFF_TRACK("\uD83D\uDD34"),
        COMPLETE("✅"),
        STALED("\uD83D\uDFE1");

        private final String icon;

        Status(String icon) {
            this.icon = icon;
        }

        public String icon() {
            return icon;
        }
    }