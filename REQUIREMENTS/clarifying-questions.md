# Clarifying Questions for YAPPY Engine Requirements

## Architecture & Orchestration

1. **Service Naming Convention**
   - You mentioned modules follow a naming convention for input/output topics. Can you provide specific examples of this convention?
   We are going to remove the output!  But it's the same name as the pipestep id.  So every pipestep will get two topics - {pipestep.id}.
     input and {pipestep.id}.dlq
   
   - How does the engine determine which Kafka topic to forward messages to based on the service name?
   The config model dictates this.  There are two types of kafka forwarding - to a specific topic or to a whitelisted topic.  If it's 
     another service, it uses the naming convention so the engine just needs to know the naming convention.  This way, the pipestream 
     just needs to just look up it's pipestep id and it can figure out where it needs to go without any input from the pipestream.  Does 
     that make sense? This is a requirement I have to reiterate often so the more I can clarify it the better.


2. **Dynamic Configuration & System Statistics**
   - What specific system statistics will the engine monitor to make routing decisions?
It's configure where it routes.  Now, there's a whole yappy-kafka-slot-manager project that handles a scaling issue with kafka: we work 
     with the number of partitions, CPU, an many other things that the code does.  A lot of tests were written for this.  Check out 
     yappy-kafka-slot-manager for more info
- 
- 
  - How frequently should the engine adapt its routing based on these statistics?
  Routing has consul watches attached to it.  
  

  - What are some examples of configuration changes that would trigger routing changes?
If the service is down or errors out all calls, it saves it to a dead letterll

3. **Message Routing Decision**
   - When does the engine decide to route via gRPC vs forwarding to Kafka?
- It's in the config and already a grpah format.  Look at the structure of consul-config models

  - Is this decision based on configuration, system load, or message content?
mainly just configuration
  - Can a single pipeline step output to both gRPC and Kafka?
EVERYTHING is fan-in and fan-out via grpc or kafka - that was very clear in current_instructions.md

## Module Registration & Discovery

4. **CI/CD Integration**
   - What information does the CI/CD system have about modules during deployment?
This functionaltiy was done - look at the old project.  The CICD knows the name of the service, the URL and port the module.  The module 
     has an API call to advertise it's JSON and service info.

  - How does CI/CD know which engine endpoint to use for registration?
Consul - it WAS in the last version -  I hope you didn't delete that....

  - Should the CLI support bulk registration of multiple modules?

5. **Module Identity**
   - How are modules uniquely identified (service name, version, instance ID)?
   - Can multiple versions of the same module run simultaneously?
   - How does the engine handle version compatibility?

6. **Health Check Requirements**
   - You mentioned all services must implement health checks. What's the minimum health check response required?
   - Should health checks include configuration validation status?
   - What timeout values should be used for health checks?

## Consul Integration

7. **Consul Organization**
   - What's the preferred Consul KV structure for different types of configuration?
   - Should service metadata be stored in Consul tags or KV?
   - How should multi-datacenter Consul deployments be handled?

8. **Service Discovery**
   - Should the engine cache Consul query results? If so, for how long?
   - How should the engine handle Consul connection failures?
   - What's the strategy for selecting instances when multiple are available?

## Pipeline Configuration

9. **Pipeline Definition**
   - How are pipeline steps connected/defined in configuration?
   - Can pipelines have conditional routing or branching?
   - How are pipeline changes deployed without disrupting running flows?

10. **Error Handling**
    - How should the engine handle module failures during processing?
    - Should failed messages be retried automatically?
    - Where should error messages and failed processing attempts be logged?

## Operational Concerns

11. **Scaling & Performance**
    - Should the engine implement connection pooling for gRPC connections?
    - What's the expected message throughput per engine instance?
    - How many concurrent module connections should an engine support?

12. **Monitoring & Observability**
    - What metrics should the engine expose about routing decisions?
    - Should the engine implement distributed tracing?
    - How detailed should the status API responses be?

13. **Security**
    - Should modules authenticate to the engine during registration?
    - How should sensitive configuration values be handled?
    - Is mTLS required for gRPC connections?

## Module Requirements

14. **Module Interface**
    - Should modules implement any interface beyond `PipeStepProcessor`?
    - Are there required metadata fields modules must provide?
    - Can modules declare their input/output message types?

15. **Module Configuration**
    - How should modules receive their configuration?
    - Should modules watch for configuration changes or restart?
    - What's the maximum acceptable module startup time?

## Edge Cases

16. **Failure Scenarios**
    - What happens if a module is deployed but registration fails?
    - How should the engine handle "zombie" registrations (module gone but still in Consul)?
    - What's the recovery process if Consul data is lost?

17. **Network Partitions**
    - How should the engine behave during network partitions?
    - Should it fail-fast or queue messages?
    - What's the strategy for handling partial failures?

## Future Considerations

18. **Kafka Integration**
    - Will modules ever read directly from Kafka or only through the engine?
    - How will Kafka topic management work?
    - Should the engine implement Kafka transaction support?

19. **Multi-Cluster**
    - Will engines need to route across cluster boundaries?
    - How will service naming work across clusters?
    - Is there a global service registry planned?

20. **Migration & Compatibility**
    - How will existing modules be migrated to this architecture?
    - What's the plan for backward compatibility?
    - Should the engine support multiple protocol versions?