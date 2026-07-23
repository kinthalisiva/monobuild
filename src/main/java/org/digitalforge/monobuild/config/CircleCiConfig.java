package org.digitalforge.monobuild.config;

import java.util.List;
import java.util.Map;

public class CircleCiConfig {

    private Map<String, JobConfig> jobs;

    public Map<String, JobConfig> getJobs() {
        return (jobs != null) ? jobs : Map.of();
    }

    public CircleCiConfig setJobs(Map<String, JobConfig> jobs) {
        this.jobs = jobs;
        return this;
    }

    public static class JobConfig {

        private List<String> context;

        public List<String> getContext() {
            return (context != null) ? context: List.of();
        }

        public JobConfig setContext(List<String> context) {
            this.context = context;
            return this;
        }
    }

}
