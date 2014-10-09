package jp.michikusa.chitose.refjava.cli;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Predicate;
import com.google.common.io.Files;

import java.io.File;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import jp.michikusa.chitose.refjava.util.Inputs;

import lombok.Getter;
import lombok.Setter;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.spi.FileOptionHandler;

import static com.google.common.base.Predicates.containsPattern;
import static com.google.common.collect.Iterables.filter;
import static com.google.common.collect.Iterables.transform;

import static java.lang.System.getenv;

public class Generator
    implements Runnable
{
    public static class CommandOption
        extends CommonOption
    {
        @Getter
        @Setter
        @Option(name= "--docletpath", required= true)
        private File docletpath;

        @Getter
        @Setter
        @Option(name= "--doclet", required= true)
        private String doclet;

        @Getter
        @Setter
        @Argument(index= 0, required= true, handler= FileOptionHandler.class)
        private File jarpath;
    }

    public Generator(CommandOption option)
    {
        this.option= option;
    }

    @Override
    public void run()
    {
        try
        {
            final Future<?> unpackTask= this.unpack();
            final Iterable<CharSequence> filenames= this.listFiles();

            unpackTask.get();

            this.generate(filenames);
        }
        catch(Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    private void generate(Iterable<? extends CharSequence> filenames)
    {
        try
        {
            final ProcessBuilder builder= new ProcessBuilder(
                    new File(getenv("JAVA_HOME"), "bin/javadoc").getAbsolutePath(),
                    "-docletpath", this.option.getDocletpath().getAbsolutePath(),
                    "-doclet", this.option.getDoclet(),
                    "-ofile", new File(this.option.getDataDir(), "output").getAbsolutePath(),
                    "-J-Xmx1024m",
                    "@ref_java_files"
                )
                .directory(this.option.getWorkDir())
            ;

            Files.write(Joiner.on('\n').join(filenames), new File(this.option.getWorkDir(), "ref_java_files"), Charset.forName("UTF-8"));

            final Process process= builder.start();

            final Future<CharSequence> out= Inputs.readAll(process.getInputStream());
            final Future<CharSequence> err= Inputs.readAll(process.getErrorStream());

            System.out.println(out.get());
            System.out.println(err.get());
        }
        catch(Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    private Iterable<CharSequence> listPackages()
    {
        final Set<CharSequence> s= new HashSet<CharSequence>();
        for(final CharSequence path : this.listFiles())
        {
            s.add(path.toString().replaceFirst("[^/]+$", "").replaceFirst("/$", "").replace('/', '.'));
        }
        return s;
    }

    private Iterable<CharSequence> listFiles()
    {
        try
        {
            final ProcessBuilder builder= new ProcessBuilder(
                    new File(getenv("JAVA_HOME"), "bin/jar").getAbsolutePath(),
                    "-tf", this.option.getJarpath().getAbsolutePath()
                )
                .directory(this.option.getWorkDir())
            ;
            final Process process= builder.start();

            final Future<CharSequence> out= Inputs.readAll(process.getInputStream());
            final Future<CharSequence> err= Inputs.readAll(process.getErrorStream());

            if(err.get().length() <= 0)
            {
                final Iterable<CharSequence> files= Arrays.<CharSequence>asList(out.get().toString().split("\\n"));

                return filter(files, containsPattern("\\.java$"));
            }
            else
            {
                throw new RuntimeException(err.get().toString());
            }
        }
        catch(Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    private Future<?> unpack()
    {
        try
        {
            final ProcessBuilder builder= new ProcessBuilder(
                    new File(getenv("JAVA_HOME"), "bin/jar").getAbsolutePath(),
                    "-xf", this.option.getJarpath().getAbsolutePath()
                )
                .directory(this.option.getWorkDir())
                .redirectErrorStream(true)
            ;
            final Process process= builder.start();

            return Inputs.readAll(process.getInputStream());
        }
        catch(Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args)
    {
        final Generator.CommandOption option= new Generator.CommandOption();
        final CmdLineParser parser= new CmdLineParser(option);

        try
        {
            parser.parseArgument(args);
        }
        catch(CmdLineException e)
        {
            e.printStackTrace();
            return;
        }

        final ExecutorService service= Executors.newCachedThreadPool();
        service.execute(new Generator(option){
            @Override
            public void run()
            {
                try
                {
                    super.run();
                    service.shutdown();

                    if(!service.awaitTermination(100, TimeUnit.MILLISECONDS))
                    {
                        service.shutdownNow();
                    }
                }
                catch(InterruptedException e)
                {
                    service.shutdownNow();
                }
            }
        });
    }

    private final CommandOption option;
}
