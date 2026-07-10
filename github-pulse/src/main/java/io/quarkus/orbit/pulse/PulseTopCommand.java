package io.quarkus.orbit.pulse;

import picocli.CommandLine;

@CommandLine.Command(
        name = "pulse",
        mixinStandardHelpOptions = true,
        subcommands = {AnalyzeCommand.class, ScoresCommand.class}
)
public class PulseTopCommand {
}
