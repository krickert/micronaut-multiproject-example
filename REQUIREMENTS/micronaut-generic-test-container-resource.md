There are cases where the provided test resources modules don't support your use case.
For example, there is no built-in test resource for supplying a dummy SMTP server, but you may need such a resource for testing your application.

In this case, Micronaut Test Resources provide generic support for starting arbitrary test containers.
Unlike the other resources, this will require adding configuration to your application to make it work.

Let's illustrate how we can declare an SMTP test container (here using YAML configuration) for use with https://micronaut-projects.github.io/micronaut-email[Micronaut Email]:

[configuration]
----
test-resources:
containers:
fakesmtp:
image-name: ghusta/fakesmtp
hostnames:
- smtp.host
exposed-ports:
- smtp.port: 25
----

- `fakesmtp` names the test container
- The `hostnames` declare what properties will be resolved with the value of the container host name. The `smtp.host` property will be resolved with the container host name
- The `exposed-ports` declares what ports the container exposes. `smtp.port` will be set to the value of the mapped port `25`

Then the values `smtp.host` and `smtp.port` can for example be used in a bean configuration:

[source,java]
----
@Singleton
public class JavamailSessionProvider implements SessionProvider {
@Value("${smtp.host}")          // <1>
private String smtpHost;

    @Value("${smtp.port}")          // <2>
    private String smtpPort;


    @Override
    public Session session() {
        Properties props = new Properties();
        props.put("mail.smtp.host", smtpHost);
        props.put("mail.smtp.port", smtpPort);
        return Session.getDefaultInstance(props);
    }
}
----
<1> The `smtp.host` property that we exposed in the test container configuration
<2> The `smtp.port` property that we exposed in the test container configuration

In addition, generic containers can bind file system resources (either read-only or read-write):

[configuration]
----
test-resources:
containers:
mycontainer:
image-name: my/container
hostnames:
- my.service.host
exposed-ports:
- my.service.port: 1883
ro-fs-bind:
- "path/to/local/readonly-file.conf": /path/to/container/readonly-file.conf
rw-fs-bind:
- "path/to/local/readwrite-file.conf": /path/to/container/readwrite-file.conf
----

Furthermore, generic containers can use tmpfs mappings (in read-only or read-write mode):

[configuration]
----
test-resources:
containers:
mycontainer:
image-name: my/container
hostnames:
- my.service.host
exposed-ports:
- my.service.port: 1883
ro-tmpfs-mappings:
- /path/to/readonly/container/file
rw-tmpfs-mappings:
- /path/to/readwrite/container/file
----

It is also possible to copy files from the host to the container:

[configuration]
----
test-resources:
containers:
mycontainer:
image-name: my/container
hostnames:
- my.service.host
exposed-ports:
- my.service.port: 1883
copy-to-container:
- path/to/local/readonly-file.conf: /path/to/container/file.conf
- classpath:/file/on/classpath.conf: /path/to/container/other.conf
----

In case you want to copy a file found on classpath, the source path must be prefixed with `classpath:`.

The following properties are also supported:

- `command`: Set the command that should be run in the container
- `env`: the map of environment variables
- `labels`: the map of labels
- `startup-timeout`: the container startup timeout

e.g:

[configuration]
----
test-resources:
containers:
mycontainer:
image-name: my/container
hostnames:
- my.service.host
exposed-ports:
- my.service.port: 1883
command: "some.sh"
env:
- MY_ENV_VAR: "my value"
labels:
- my.label: "my value"
startup-timeout: 120s
----

Please refer to the <<configurationreference#io.micronaut.testresources.testcontainers.TestContainersConfiguration,configuration properties reference>> for details.

[[advanced-networking]]
== Advanced networking

By default, each container is spawned in its own network.
It is possible to let containers communicate with each other by declaring the network they belong to:

[configuration]
----
test-resources:
containers:
producer:
image-name: alpine:3.14
command:
- /bin/sh
- "-c"
- "while true ; do printf 'HTTP/1.1 200 OK\\n\\nyay' | nc -l -p 8080; done"
network: custom
network-aliases: bob
hostnames: producer.host
consumer:
image-name: alpine:3.14
command: top
network: custom
network-aliases: alice
hostnames: consumer.host
----

The `network` key is used to declare the network containers use.
The `network-aliases` can be used to assign host names to the containers.

The `network-mode` can be used to set the network mode (e.g. including 'host', 'bridge', 'none' or the name of an existing named network.)

== Wait strategies

Micronaut Test Resources uses the default https://www.testcontainers.org/features/startup_and_waits/[Testcontainers wait strategies].

It is however possible to override the default by configuration.

[configuration]
----
test-resources:
containers:
mycontainer:
image-name: my/container
wait-strategy:
log:
regex: ".*Started.*"
----

The following wait strategies are supported:

- `log` strategy with the following properties:
    - `regex`: the regular expression to match
    - `times`: the number of times the regex should be matched
- `http` strategy with the following properties:
    - `path`: the path to check
    - `tls`: if TLS should be enabled
    - `port`: the port to listen on
    - `status-code`: the list of expected status codes
- `port` strategy (this is the default strategy)
- `healthcheck`: Docker healthcheck strategy

Multiple strategies can be configured, in which case they are all applied (waits until all conditions are met):

[configuration]
----
test-resources:
containers:
mycontainer:
image-name: my/container
wait-strategy:
port:
log:
regex: ".*Started.*"
----

It is possible to configure the `all` strategy itself:

[configuration]
----
test-resources:
containers:
mycontainer:
image-name: my/container
wait-strategy:
port:
log:
regex: ".*Started.*"
all:
timeout: 120s
----

== Dependencies between containers

In some cases, your containers may require other containers to be started before they start.
You can declare such dependencies with the `depends-on` property:

[configuration]
----
test-resources:
containers:
mycontainer:
image-name: "my/container"
depends-on: other
# ...
other:
image-name: "my/other"
# ...
----

It's worth noting that such containers need to be declared on the <<#advanced-networking,same network>> in order to be able to communicate with each other.

WARNING: Dependencies between containers **only work between generic containers**. It is not possible to create a dependency between a generic container and a container created with the other test resources resolvers. For example, you cannot add a dependency on a container which provides a MySQL database by adding a `depends-on: mysql`.
