package jp.michikusa.chitose.refjava.cli;

import java.io.File;

import lombok.Getter;
import lombok.Setter;

import org.kohsuke.args4j.Option;

class CommonOption
{
    @Getter
    @Setter
    @Option(name= "--workdir", required= true)
    private File workDir;

    @Getter
    @Setter
    @Option(name= "--datadir", required= true)
    private File dataDir;

    @Getter
    @Option(name= "--help", aliases= "-h", help= true)
    private boolean help;
}
