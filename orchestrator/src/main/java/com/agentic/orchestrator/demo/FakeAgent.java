package com.agentic.orchestrator.demo;

import com.agentic.orchestrator.artifacts.ArtifactDraft;
import com.agentic.orchestrator.domain.DecisionRecord;
import com.agentic.orchestrator.engine.CompensationContext;
import com.agentic.orchestrator.engine.NodeContext;
import com.agentic.orchestrator.engine.NodeExecutor;
import com.agentic.orchestrator.engine.NodeResult;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Stand-in agent for engine tests and the Day-1 demo. Output depends only on the node and its inputs,
 * so an unchanged upstream produces an unchanged digest, which is what lets approvals survive reruns.
 */
public final class FakeAgent implements NodeExecutor {
    private final String key;
    private final Duration workTime;
    private final long tokensPerCall;

    public FakeAgent(String key, Duration workTime, long tokensPerCall) {
        this.key = key;
        this.workTime = workTime;
        this.tokensPerCall = tokensPerCall;
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public NodeResult execute(NodeContext ctx) throws Exception {
        if (!workTime.isZero()) {
            Thread.sleep(workTime);
        }
        String inputs = ctx.inputs().entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue().stream().map(a -> a.hash().substring(0, 8))
                        .collect(Collectors.joining("+")))
                .collect(Collectors.joining(", "));
        String body = "# " + ctx.node().type() + ": " + ctx.node().id() + "\n"
                + ctx.node().description() + "\n"
                + "inputs: [" + inputs + "]\n"
                + "acceptance criteria covered: " + ctx.node().acceptanceCriteria() + "\n";
        NodeResult result = new NodeResult(List.of(ArtifactDraft.text(ctx.node().id() + ".md", body)),
                tokensPerCall, tokensPerCall * 0.00001, List.of(), "fake output for " + ctx.node().id());
        if (ctx.node().id().equals("design")) {
            result = result.withDecision(new DecisionRecord("Short-code generation",
                    "Codes must be unique and short", List.of("random + collision check", "sequence + base62 permutation"),
                    "sequence + base62 permutation", "no collision retries, predictable latency"));
        }
        return result;
    }

    @Override
    public String compensate(CompensationContext ctx) {
        return "reverted " + ctx.outputHashes().size() + " artifact(s) produced by '" + ctx.node().id() + "'";
    }
}
