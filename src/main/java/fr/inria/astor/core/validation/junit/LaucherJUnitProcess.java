package fr.inria.astor.core.validation.junit;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;

import fr.inria.astor.approaches.tos.core.MetaGenerator;
import fr.inria.astor.core.setup.ConfigurationProperties;
import fr.inria.astor.core.setup.ProjectConfiguration;
import fr.inria.astor.core.validation.results.TestResult;

/**
 * Launches a JunitExecutor-process and parses its output.
 * The normal JUnit runner cannot be used directly due to issues with the classpaths:
 * As Programs and their variants share the namespace, it is not easy to "just run" it.
 * Therefore the Classpath is adjusted for every mutant and the executor is run.
 *
 * @author Matias Martinez, matias.martinez@inria.fr
 *
 */
public class LaucherJUnitProcess {

	protected Logger log = Logger.getLogger(Thread.currentThread().getName());
	boolean avoidInterruption = false;
	final boolean SET_TIMEZONE = false;

	public LaucherJUnitProcess(boolean avoidInterruption) {
		super();
		this.avoidInterruption = avoidInterruption;
	}

	public LaucherJUnitProcess() {
		this(false);
	}

	public TestResult execute(String jvmPath, URL[] classpath, List<String> classesToExecute, int waitTime) {
		return execute(jvmPath, urlArrayToString(classpath), classesToExecute, waitTime);
	}

	boolean outputInFile = ConfigurationProperties.getPropertyBool("processoutputinfile");

	public TestResult execute(String jvmPath, String classpath, List<String> classesToExecute, int waitTime) {
		Process p = null;
		// In the current version, this would point to ~/lib/jvm/zulu7/java which does not exist
		// It must point to ~/lib/jvm/zulu7/bin/java
		// Which is addressed below
		String adjustedJvmPath = jvmPath;
		// Check if the original javapath exists, if not try to adjust it
		if(!(new File(adjustedJvmPath).exists())) {
			adjustedJvmPath += adjustedJvmPath+File.separator + "bin" + File.separator + "java";
		}
		// Short check for existence on the adjusted java path
		if(!(new File(adjustedJvmPath).exists())){
			throw new UnsupportedOperationException("There was no Java version at given JVMPath " + adjustedJvmPath);
		}

		List<String> cls = new ArrayList<>(classesToExecute);

		/*
		Things the classpath must contain at this point:
		- Astor classes
		- The classes for the system under test (Maybe: variant-bins)
		- The classes for the tests of the SUT
		- The dependencies of astor
		- The dependencies of the SUT
		- Maybe: Java 7 TestExecutor (For legacy code, it's called jtestex7)
		- Maybe: Additional JUnit Deps
		 */
		String adjustedClasspath = classpath;
		//adjustedClasspath = adjustedClasspath.replace("\"","").replace("\"","");
		if (ConfigurationProperties.getPropertyBool("runjava7code") || ProjectConfiguration.isJDKLowerThan8()) {
			adjustedClasspath = "\""+(new File(ConfigurationProperties.getProperty("executorjar")).getAbsolutePath())+"\""
					+ File.pathSeparator + adjustedClasspath;
		}
		// This should add the tests to the cp
		String testlocation = (new File((ConfigurationProperties.getProperty("location")))).getAbsolutePath();
		adjustedClasspath += adjustedClasspath
				//+ File.pathSeparator
					+ "\"" + testlocation + "\"";

		try {
			File ftemp = null;
			if (outputInFile)
				ftemp = File.createTempFile("out", "txt");

			List<String> command = new ArrayList<String>();

			command.add(adjustedJvmPath);
			//command.add("java");
			command.add("-Xmx2048m");

			String[] ids = ConfigurationProperties.getProperty(MetaGenerator.METALL).split(File.pathSeparator);
			// The mutid keeps track of the program-variant / patch candidate.
			// The naming over the classes is the same despite different content, which is expressed as the mutid
			for (String mutid : ids) {
				command.add("-D" + MetaGenerator.MUT_IDENTIFIER + mutid + "="
						+ ConfigurationProperties.getProperty(MetaGenerator.MUT_IDENTIFIER + mutid));
			}

			command.add("-cp");
			command.add(adjustedClasspath);
			command.add(laucherClassName().getCanonicalName());
			command.addAll(cls);
			//log.error("CP is " + newClasspath);
			printCommandToExecute(command, waitTime);

			ProcessBuilder pb = new ProcessBuilder("/bin/bash");


			//TODO: This should be the nicer approach, once everything works
			ProcessBuilder replacement = new ProcessBuilder(command)
					.redirectErrorStream(true)	// whether to merge error into output
					.redirectOutput(ProcessBuilder.Redirect.INHERIT) // Puts the output to this process' stdout stderr
			;
			Process replacementProcess = replacement.start();
			replacementProcess.waitFor();

			log.error("Full Command is " + toString(command));

			if (outputInFile) {
				pb.redirectOutput(ftemp);
			} else
				pb.redirectOutput();
			pb.redirectErrorStream(true);
			pb.directory(new File((ConfigurationProperties.getProperty("location"))));
			long t_start = System.currentTimeMillis();
			p = pb.start();

			BufferedWriter p_stdin = new BufferedWriter(new OutputStreamWriter(p.getOutputStream()));

			try {
				// Set up the timezone
				if (SET_TIMEZONE) {
					String timeZone = ConfigurationProperties.getProperty("timezone");
					p_stdin.write("TZ=\"" + timeZone + "\"");
					p_stdin.newLine();
					p_stdin.flush();
					p_stdin.write("export TZ");
					p_stdin.newLine();
					p_stdin.flush();
					p_stdin.write("echo $TZ");
					p_stdin.newLine();
					p_stdin.flush();
				}
				// Writing the command
				p_stdin.write(toString(command));
				p_stdin.newLine();
				p_stdin.flush();

				// end
				p_stdin.write("exit");
				p_stdin.newLine();
				p_stdin.flush();
			} catch (IOException e) {
				log.error(e);
			}

			//
			if (!p.waitFor(waitTime, TimeUnit.MILLISECONDS)) {
				killProcess(p, waitTime);

				return null;
			}
			long t_end = System.currentTimeMillis();
			// log.debug("Execution time " + ((t_end - t_start) / 1000) + " seconds");

			if (!avoidInterruption) {
				// We force obtaining the exit value.
				p.exitValue();
			}

			BufferedReader output = null;
			if (outputInFile)
				output = new BufferedReader(new FileReader(ftemp.getAbsolutePath()));
			else
				output = new BufferedReader(new InputStreamReader(p.getInputStream()));
			TestResult tr = getTestResult(output);
			if(tr == null)
				log.warn("The Process that runs JUnit could not retrieve results for " + classesToExecute.toArray().toString());
			p.destroyForcibly();
			return tr;
		} catch (IOException | InterruptedException | IllegalThreadStateException ex) {
			log.info("The Process that runs JUnit test cases had problems: " + ex.getMessage());
			killProcess(p, waitTime);
		}
		return null;
	}

	/**
	 * Workarrond. I will be solved when migrating to java 9.
	 * https://docs.oracle.com/javase/9/docs/api/java/lang/Process.html#descendants--
	 * 
	 * @param waitTime
	 */
	private void killProcess(Process p, int waitTime) {
		if (p == null)
			return;

		Object pid = null;
		try {
			Field f = p.getClass().getDeclaredField("pid");
			f.setAccessible(true);
			pid = f.get(p);
			log.debug("-Killed id: pid->" + pid);

		} catch (Exception e) {
			log.error(e);
		}
		p.destroyForcibly();

		log.info("The Process that runs JUnit test cases did not terminate within waitTime of "
				+ TimeUnit.MILLISECONDS.toSeconds(waitTime) + " seconds");
		log.info("Killed the Process that runs JUnit test cases " + pid);

		// workarrond!!
		if (ConfigurationProperties.getPropertyBool("forcesubprocesskilling")) {
			Integer subprocessid = Integer.valueOf(pid.toString()) + 1;
			try {
				log.debug("Killing subprocess " + subprocessid);
				Process process = new ProcessBuilder(new String[] { "kill", subprocessid.toString() }).start();
				process.waitFor();
			} catch (Exception e) {
				log.error("Problems killing subprocess " + subprocessid);
				log.error(e);
			}
		}

	}

	protected String urlArrayToString(URL[] urls) {
		String s = "";
		for (int i = 0; i < urls.length; i++) {
			URL url = urls[i];
			s += "\"" + url.getPath() + "\"" + File.pathSeparator;
		}
		return s;
	}

	protected String getProcessError(InputStream str) {
		String out = "";
		try {
			BufferedReader in = new BufferedReader(new InputStreamReader(str));
			String line;
			while ((line = in.readLine()) != null) {
				out += line + "\n";
			}
			in.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return out;
	}

	private void printCommandToExecute(List<String> command, int waitTime) {
		String commandString = toString(command);
		int trunk = ConfigurationProperties.getPropertyInt("commandTrunk");
		String commandToPrint = (trunk != 0 && commandString.length() > trunk)
				? (commandString.substring(0, trunk) + "..AND " + (commandString.length() - trunk) + " CHARS MORE...")
				: commandString;
		log.debug("Executing process: (timeout" + waitTime / 1000 + "secs) \n" + commandToPrint);
	}

	private String toString(List<String> command) {
		String commandString = command.toString().replace("[", "").replace("]", "").replace(",", " ");
		return commandString;
	}

	public Class laucherClassName() {
		if (ConfigurationProperties.getPropertyBool("logtestexecution"))
			return JUnitExternalExecutor.class;
		else
			return JUnitNologExternalExecutor.class;

	}

	/**
	 * This method analyze the output of the junit executor (i.e.,
	 * {@link JUnitNologExternalExecutor}) and return an entity called TestResult with the
	 * result of the test execution
	 *
	 * @param in
	 * @return
	 */
	protected TestResult getTestResult(BufferedReader in) {
		log.debug("Analyzing output from process");
		TestResult tr = new TestResult();
		boolean success = false;
		String processOut = "";
		try {
			String line;
			while ((line = in.readLine()) != null) {
				processOut += line + "\n";
				if (line.startsWith(JUnitExternalExecutor.OUTSEP)) {
					String[] resultPrinted = line.split(JUnitExternalExecutor.OUTSEP);
					int nrtc = Integer.valueOf(resultPrinted[1]);
					tr.casesExecuted = nrtc;
					int nrfailing = Integer.valueOf(resultPrinted[2]);
					tr.failures = nrfailing;
					if (resultPrinted.length > 3 && !"".equals(resultPrinted[3])) {
						String[] failingTestList = resultPrinted[3].replace("[", "").replace("]", "").split(",");
						for (String failingTest : failingTestList) {
							failingTest = failingTest.trim();
							if (!failingTest.isEmpty() && !failingTest.equals("-"))
								tr.failTest.add(failingTest);
						}
					}
					success = true;
				}
			}
			// log.debug("Process output:\n"+ out);
			in.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
		if (success)
			return tr;
		else {
			log.error("Error reading the validation process\n output: \n" + processOut);
			return null;
		}
	}

}
