package com.agentic.orchestrator.gates;

/** Fails when an attempt produced no output, or an empty one. */
public final class OutputsPresentGate implements Gate {
    public static final String ID = "outputs-present";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public GateResult evaluate(GateContext ctx) {
        if (ctx.outputs().isEmpty()) {
            return GateResult.fail(ID, "no artifacts produced");
        }
        return ctx.outputs().stream()
                .filter(o -> o.content().length == 0)
                .findFirst()
                .map(o -> GateResult.fail(ID, "artifact '" + o.name() + "' is empty"))
                .orElseGet(() -> GateResult.pass(ID, ctx.outputs().size() + " artifact(s) present"));
    }
}
