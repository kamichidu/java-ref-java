package jp.michikusa.chitose.refjava.cli;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Predicate;
import com.google.common.base.Strings;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jp.michikusa.chitose.refjava.util.Inputs;

import lombok.Getter;
import lombok.Setter;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.spi.FileOptionHandler;
import org.kohsuke.args4j.spi.MultiFileOptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Predicates.containsPattern;
import static com.google.common.base.Strings.*;
import static com.google.common.collect.Iterables.*;
import static com.google.common.collect.Iterables.filter;

import static java.lang.System.getenv;
import static java.util.Arrays.asList;

public class Generator
    implements Runnable
{
    public static class CommandOption
        extends CommonOption
    {
        public Iterable<File> getJarpaths()
        {
            return asList(this.jarpaths);
        }

        public void setJarpaths(Iterable<? extends File> jarpaths)
        {
            this.jarpaths= toArray(jarpaths, File.class);
        }

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
        @Option(name= "--encoding", required= false)
        private String encoding= "UTF-8";

        @Getter
        @Setter
        @Option(name= "--vmargs")
        private String vmargs= "";

        @Argument(required= true, metaVar="jar file", handler= FileOptionHandler.class, multiValued=true)
        private File[] jarpaths;
    }

    public Generator(CommandOption option)
    {
        this.option= option;
    }

    @Override
    public void run()
    {
        logger.info("Starting to generate javadoc with arguments below:");
        logger.info("  workdir=`{}'", this.option.getWorkDir());
        logger.info("  datadir=`{}'", this.option.getDataDir());
        logger.info("  doclet=`{}'", this.option.getDoclet());
        logger.info("  docletpath=`{}'", this.option.getDocletpath());
        logger.info("  encoding=`{}'", this.option.getEncoding());
        logger.info("  vmargs=`{}'", this.option.getVmargs());
        logger.info("  jarpaths=`{}'", this.option.getJarpaths());

        try
        {
            if(!this.option.getWorkDir().exists())
            {
                logger.info("Given `workdir' doesn't exist, creating...");
                this.option.getWorkDir().mkdirs();
                logger.info("`workdir' was created.");
            }
            if(!this.option.getDataDir().exists())
            {
                logger.info("Given `datadir' doesn't exist, creating...");
                this.option.getDataDir().mkdirs();
                logger.info("`datadir' was created.");
            }
            for(final File jarpath : this.option.getJarpaths())
            {
                if(!jarpath.exists())
                {
                    throw new FileNotFoundException(jarpath.getAbsolutePath());
                }
            }

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
            final List<String> command= new LinkedList<String>();

            command.add(new File(getenv("JAVA_HOME"), "bin/javadoc").getAbsolutePath());
            command.add("-docletpath");
            command.add(this.option.getDocletpath().getAbsolutePath());
            command.add("-doclet");
            command.add(this.option.getDoclet());
            command.add("-ofile");
            command.add(new File(this.option.getDataDir(), "output").getAbsolutePath());
            if(!isNullOrEmpty(this.option.getVmargs()))
            {
                command.add("-J\"" + this.option.getVmargs() + "\"");
            }
            command.add("-encoding");
            command.add(this.option.getEncoding());
            command.add("@ref_java_files");

            final ProcessBuilder builder= new ProcessBuilder(command)
                .directory(this.option.getWorkDir())
            ;

            logger.info("Generate javadoc by filenames follows: {}", filenames);
            Files.write(Joiner.on('\n').join(filenames), new File(this.option.getWorkDir(), "ref_java_files"), Charset.forName("UTF-8"));

            final Process process= builder.start();

            final Future<CharSequence> out= Inputs.readAll(process.getInputStream());
            final Future<CharSequence> err= Inputs.readAll(process.getErrorStream());

            for(final CharSequence line : out.get().toString().split("\\r?\\n"))
            {
                logger.info("`javadoc' command's output: {}", line);
            }
            for(final CharSequence line : err.get().toString().split("\\r?\\n"))
            {
                logger.info("`javadoc' command's error output: {}", line);
            }
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
        final List<Future<CharSequence>> outs= new ArrayList<Future<CharSequence>>(size(this.option.getJarpaths()));
        final List<Future<CharSequence>> errs= new ArrayList<Future<CharSequence>>(size(this.option.getJarpaths()));

        for(final File jarpath : this.option.getJarpaths())
        {
            try
            {
                final ProcessBuilder builder= new ProcessBuilder(
                        new File(getenv("JAVA_HOME"), "bin/jar").getAbsolutePath(),
                        "-tf", jarpath.getAbsolutePath()
                    )
                    .directory(this.option.getWorkDir())
                ;
                final Process process= builder.start();

                outs.add(Inputs.readAll(process.getInputStream()));
                errs.add(Inputs.readAll(process.getErrorStream()));
            }
            catch(Exception e)
            {
                throw new RuntimeException(e);
            }
        }

        final Set<CharSequence> files= new HashSet<CharSequence>();

        for(int i= 0; i < outs.size(); ++i)
        {
            try
            {
                final Future<CharSequence> out= outs.get(i);
                final Future<CharSequence> err= errs.get(i);

                if(err.get().length() <= 0)
                {
                    final Iterable<CharSequence> lines= Arrays.<CharSequence>asList(out.get().toString().split("\\n"));

                    addAll(files, filter(lines, containsPattern("\\.java$")));
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

        return files;
    }

    private Future<?> unpack()
    {
        class Unpacker
            implements Runnable
        {
            public Unpacker(File workdir, File jarpath)
            {
                this.workdir= workdir;
                this.jarpath= jarpath;
            }

            @Override
            public void run()
            {
                try
                {
                    final ProcessBuilder builder= new ProcessBuilder(
                            new File(getenv("JAVA_HOME"), "bin/jar").getAbsolutePath(),
                            "-xf", this.jarpath.getAbsolutePath()
                        )
                        .directory(this.workdir)
                        .redirectErrorStream(true)
                    ;
                    final Process process= builder.start();

                    final Future<?> task= Inputs.readAll(process.getInputStream());

                    task.get();
                }
                catch(Exception e)
                {
                    throw new RuntimeException(e);
                }
            }

            private final File workdir;

            private final File jarpath;
        }

        class ComplexFuture
            implements Future<Void>
        {
            public ComplexFuture(Iterable<? extends Future<?>> futures)
            {
                this.futures= futures;
            }

            @Override
            public boolean cancel(boolean mayInterruptIfRunning)
            {
                boolean res= true;
                for(final Future<?> future : this.futures)
                {
                    res= res && future.cancel(mayInterruptIfRunning);
                }
                return res;
            }

            @Override
            public boolean isCancelled()
            {
                boolean res= true;
                for(final Future<?> future : this.futures)
                {
                    res= res && future.isCancelled();
                }
                return res;
            }

            @Override
            public boolean isDone()
            {
                boolean res= true;
                for(final Future<?> future : this.futures)
                {
                    res= res && future.isDone();
                }
                return res;
            }

            @Override
            public Void get()
                throws InterruptedException, ExecutionException
            {
                for(final Future<?> future : this.futures)
                {
                    future.get();
                }
                return null;
            }

            @Override
            public Void get(long timeout, TimeUnit unit)
                    throws InterruptedException, ExecutionException, TimeoutException
            {
                for(final Future<?> future : this.futures)
                {
                    future.get(timeout, unit);
                }
                return null;
            }

            private final Iterable<? extends Future<?>> futures;
        }

        try
        {
            final ExecutorService service= Executors.newCachedThreadPool();
            final List<Future<?>> futures= new ArrayList<Future<?>>(size(this.option.getJarpaths()));
            for(final File jarpath : this.option.getJarpaths())
            {
                futures.add(service.submit(new Unpacker(this.option.getWorkDir(), jarpath)));
            }
            service.shutdown();
            return new ComplexFuture(futures);
        }
        catch(Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args)
    {
        try
        {
            final Generator.CommandOption option= new Generator.CommandOption();
            final CmdLineParser parser= new CmdLineParser(option);

            try
            {
                parser.parseArgument(args);
            }
            catch(CmdLineException e)
            {
                logger.error("Invalid cli option(s) was detected.", e);
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
        catch(Exception e)
        {
            logger.error("Unexpectedly terminated.", e);
        }
    }

    private static final Logger logger= LoggerFactory.getLogger(Generator.class);

    private final CommandOption option;
}
