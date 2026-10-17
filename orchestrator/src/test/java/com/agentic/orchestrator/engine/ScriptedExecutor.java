package com.agentic.orchestrator.engine;

import com.agentic.orchestrator.artifacts.ArtifactDraft;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Test executor whose behaviour is a lambda; records every context and compensation it sees. */
final class ScriptedExecutor implements NodeExecutor {
    interface Behaviour {
        NodeResult run(NodeContext ctx) throws Exception;
    }

    final String key;
    final Behaviour behaviour;
    final List<NodeContext> calls = new CopyOnWriteArrayList<>();
    final List<String> compensated = new CopyOnWriteArrayList<>();
    final AtomicInteger concurrent = new AtomicInteger();
    final AtomicInteger maxConcurrent = new AtomicInteger();

    ScriptedExecutor(String key, Behaviour behaviour) {
        this.key = key;
        this.behaviour = behaviour;
    }

    static ScriptedExecutor echo(String key) {
        return new ScriptedExecutor(key, ctx -> NodeResult.of(
                ArtifactDraft.text(ctx.node().id() + ".txt", "output of " + ctx.node().id())).withUsage(100, 0.001));
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public NodeResult execute(NodeContext ctx) throws Exception {
        calls.add(ctx);
        int now = concurrent.incrementAndGet();
        maxConcurrent.accumulateAndGet(now, Math::max);
        try {
            return behaviour.run(ctx);
        } finally {
            concurrent.decrementAndGet();
        }
    }

    @Override
    public String compensate(CompensationContext ctx) {
        compensated.add(ctx.node().id());
        return "undone " + ctx.node().id();
    }

    long callsFor(String nodeId) {
        return calls.stream().filter(c -> c.node().id().equals(nodeId)).count();
    }
}
