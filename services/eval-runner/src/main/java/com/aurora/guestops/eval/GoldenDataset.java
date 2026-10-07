package com.aurora.guestops.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GoldenDataset(String name, String description, List<Case> cases) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Case(String id, String category, String question, String confirmationNumber, Expected expected,
                       String groundTruth) {

        public boolean hasGroundTruth() {
            return groundTruth != null && !groundTruth.isBlank();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Expected(List<String> agents, List<String> tools, List<String> approvals, Boolean blocked,
                           List<String> mustContain, List<String> mustNotContain, List<String> piiStrippedFor) {

        public List<String> agents() {
            return agents == null ? List.of() : agents;
        }

        public List<String> tools() {
            return tools == null ? List.of() : tools;
        }

        public List<String> approvals() {
            return approvals == null ? List.of() : approvals;
        }

        public List<String> mustContain() {
            return mustContain == null ? List.of() : mustContain;
        }

        public List<String> mustNotContain() {
            return mustNotContain == null ? List.of() : mustNotContain;
        }

        public List<String> piiStrippedFor() {
            return piiStrippedFor == null ? List.of() : piiStrippedFor;
        }

        public boolean expectBlocked() {
            return Boolean.TRUE.equals(blocked);
        }
    }
}
