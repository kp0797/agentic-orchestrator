package com.agentic.orchestrator.engine;

import com.agentic.orchestrator.domain.Plan;
import com.agentic.orchestrator.domain.RunBudget;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Holds the accepted plan and budget per run so a run can be resumed later. */
public interface PlanStore {
    record StoredPlan(Plan plan, RunBudget budget) {}

    void save(String runId, Plan plan, RunBudget budget);

    StoredPlan load(String runId);

    final class InMemory implements PlanStore {
        private final Map<String, StoredPlan> plans = new ConcurrentHashMap<>();

        @Override
        public void save(String runId, Plan plan, RunBudget budget) {
            plans.put(runId, new StoredPlan(plan, budget));
        }

        @Override
        public StoredPlan load(String runId) {
            StoredPlan p = plans.get(runId);
            if (p == null) {
                throw new IllegalArgumentException("unknown run " + runId);
            }
            return p;
        }
    }
}
