package io.quarkus.orbit.pulse;

import io.quarkus.picocli.runtime.annotations.TopCommand;
import picocli.CommandLine;

@TopCommand
@CommandLine.Command(
        name = "pulse",
        mixinStandardHelpOptions = true,
        subcommands = {AnalyzeCommand.class, ScoresCommand.class, ReleaseReportCommand.class, PlatformReportCommand.class}
)
public class PulseTopCommand implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().execute("analyze");
    }
}
