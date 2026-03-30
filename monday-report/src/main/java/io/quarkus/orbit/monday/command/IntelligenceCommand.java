package io.quarkus.orbit.monday.command;

import io.quarkus.picocli.runtime.annotations.TopCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@TopCommand
@Command(name = "intelligence", mixinStandardHelpOptions = true, version = "1.0",
        description = "Intelligence - GitHub activity analysis and reporting tools",
        subcommands = {
            MondayIntelligenceReportCommand.class,
            HeatmapCommand.class,
            CommandLine.HelpCommand.class
        })
public class IntelligenceCommand implements Runnable {

    @Override
    public void run() {
        // When no subcommand is specified, show help
        CommandLine.usage(this, System.out);
    }
}
