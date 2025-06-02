package com.krickert.search.pipeline.module.testing;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

/**
 * Command-line interface for testing Yappy modules.
 * This can be packaged as a standalone JAR for module developers.
 * 
 * Usage:
 *   java -jar yappy-module-tester.jar localhost 50051
 *   java -jar yappy-module-tester.jar --help
 */
@Command(
    name = "yappy-module-tester",
    mixinStandardHelpOptions = true,
    version = "1.0",
    description = "Test tool for Yappy pipeline modules (gRPC services)",
    footer = "\nExample:\n  yappy-module-tester localhost 50051\n"
)
public class ModuleTestCLI implements Callable<Integer> {
    
    @Parameters(index = "0", description = "The host where the module is running")
    private String host;
    
    @Parameters(index = "1", description = "The port where the module is listening")
    private int port;
    
    @Option(names = {"-t", "--test"}, 
            description = "Run specific test only. Options: registration, basic, config, error, performance, large, concurrent")
    private String specificTest;
    
    @Option(names = {"-v", "--verbose"}, 
            description = "Verbose output")
    private boolean verbose;
    
    @Option(names = {"--timeout"}, 
            description = "Request timeout in seconds (default: 10)",
            defaultValue = "10")
    private int timeout;
    
    @Override
    public Integer call() throws Exception {
        System.out.println("=== Yappy Module Test Suite ===");
        System.out.println("Testing module at " + host + ":" + port);
        System.out.println();
        
        ModuleTestFramework tester = new ModuleTestFramework(host, port);
        
        try {
            ModuleTestFramework.ModuleTestResult result;
            
            if (specificTest != null) {
                result = runSpecificTest(tester, specificTest);
            } else {
                result = tester.runFullTestSuite();
            }
            
            System.out.println(result.getReport());
            
            if (!result.allPassed()) {
                System.err.println("\n⚠️  Some tests failed. Please check your module implementation.");
                printTroubleshootingTips();
                return 1;
            } else {
                System.out.println("\n✅ All tests passed! Your module is ready for use.");
                return 0;
            }
            
        } finally {
            tester.shutdown();
        }
    }
    
    private ModuleTestFramework.ModuleTestResult runSpecificTest(
            ModuleTestFramework tester, String testName) {
        
        System.out.println("Running specific test: " + testName);
        
        // This would be enhanced to run individual tests
        // For now, runs the full suite
        return tester.runFullTestSuite();
    }
    
    private void printTroubleshootingTips() {
        System.err.println("\nTroubleshooting tips:");
        System.err.println("1. Ensure your module implements the PipeStepProcessor service");
        System.err.println("2. Check that getServiceRegistration returns a valid pipe_step_name");
        System.err.println("3. Verify processData handles the test document correctly");
        System.err.println("4. For configuration support, include 'json_config_schema' in context_params");
        System.err.println("\nFor more details, run with --verbose flag");
    }
    
    public static void main(String[] args) {
        int exitCode = new CommandLine(new ModuleTestCLI()).execute(args);
        System.exit(exitCode);
    }
}