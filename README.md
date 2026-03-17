# dataflow-maven-plugin
A maven plugin integrating the fluxtion toolset with maven build cycle.

## Usage

To integrate `dataflow-maven-plugin` into your build, you must define an `<execution>` block in your `pom.xml`. Since the plugin goals are already bound to the `compile` phase by default, you only need to specify the goal(s) to execute.

### Scan and generate Fluxtion builders

The `scan` goal scans the project classes for Fluxtion builder definitions and generates the corresponding event processors.

```xml
<plugin>
    <groupId>com.fluxtion.dataflow</groupId>
    <artifactId>dataflow-maven-plugin</artifactId>
    <version>1.1-SNAPSHOT</version>
    <executions>
        <execution>
            <goals>
                <goal>scan</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

### Spring to Fluxtion generation

The `springToFluxtion` goal generates a Fluxtion event processor from a Spring context definition.

```xml
<plugin>
    <groupId>com.fluxtion.dataflow</groupId>
    <artifactId>dataflow-maven-plugin</artifactId>
    <version>1.1-SNAPSHOT</version>
    <executions>
        <execution>
            <goals>
                <goal>springToFluxtion</goal>
            </goals>
            <configuration>
                <className>com.example.GeneratedProcessor</className>
                <packageName>com.example</packageName>
                <springFile>src/main/resources/spring.xml</springFile>
            </configuration>
        </execution>
    </executions>
</plugin>
```

### YAML to Fluxtion generation

The `yamlToFluxtion` goal generates a Fluxtion event processor from one or more YAML configuration files.

```xml
<plugin>
    <groupId>com.fluxtion.dataflow</groupId>
    <artifactId>dataflow-maven-plugin</artifactId>
    <version>1.1-SNAPSHOT</version>
    <executions>
        <execution>
            <goals>
                <goal>yamlToFluxtion</goal>
            </goals>
            <configuration>
                <fluxtionConfigFiles>
                    <file>src/main/resources/config.yaml</file>
                </fluxtionConfigFiles>
            </configuration>
        </execution>
    </executions>
</plugin>
```



