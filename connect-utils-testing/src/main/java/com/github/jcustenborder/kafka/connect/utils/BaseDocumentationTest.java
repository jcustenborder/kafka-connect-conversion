/**
 * Copyright © 2016 Jeremy Custenborder (jcustenborder@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.jcustenborder.kafka.connect.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import com.github.jcustenborder.kafka.connect.utils.jackson.ObjectMapperFactory;
import com.github.jcustenborder.kafka.connect.utils.templates.ImmutableConfigProviderExampleInput;
import com.github.jcustenborder.kafka.connect.utils.templates.ImmutableConverterExampleInput;
import com.github.jcustenborder.kafka.connect.utils.templates.ImmutableSchemaInput;
import com.github.jcustenborder.kafka.connect.utils.templates.ImmutableSinkConnectorExampleInput;
import com.github.jcustenborder.kafka.connect.utils.templates.ImmutableSourceConnectorExampleInput;
import com.github.jcustenborder.kafka.connect.utils.templates.ImmutableTransformationExampleInput;
import com.github.jcustenborder.kafka.connect.utils.templates.Plugin;
import com.github.jcustenborder.kafka.connect.utils.templates.PluginLoader;
import com.google.common.base.CaseFormat;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Predicate;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;
import freemarker.cache.ClassTemplateLoader;
import freemarker.ext.beans.BeansWrapper;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigValue;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.transforms.Transformation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.reflections.Reflections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;


public abstract class BaseDocumentationTest {
  private static final Logger log = LoggerFactory.getLogger(BaseDocumentationTest.class);

  protected List<Schema> schemas() {
    return Arrays.asList();
  }

  protected Package getPackage() {
    return this.getClass().getPackage();
  }

  static Configuration configuration;
  static ClassTemplateLoader loader;

  @BeforeAll
  public static void loadTemplates() {
    loader = new ClassTemplateLoader(
        BaseDocumentationTest.class,
        "templates"
    );

    configuration = new Configuration(Configuration.getVersion());
    configuration.setDefaultEncoding("UTF-8");
    configuration.setTemplateLoader(loader);
    configuration.setObjectWrapper(new BeansWrapper(Configuration.getVersion()));
    configuration.setNumberFormat("computer");
  }


  static <T> List<Class<? extends T>> list(Reflections reflections, Package pkg, Class<? extends T> cls) {
    List<Class<? extends T>> classes = reflections.getSubTypesOf(cls)
        .stream()
        .filter(c -> c.getName().startsWith(pkg.getName()))
        .filter(c -> Modifier.isPublic(c.getModifiers()))
        .filter(c -> !Modifier.isAbstract(c.getModifiers()))
        .filter((Predicate<Class<? extends T>>) aClass -> Arrays.stream(aClass.getConstructors())
            .filter(c -> Modifier.isPublic(c.getModifiers()))
            .anyMatch(c -> c.getParameterCount() == 0))
        .sorted(Comparator.comparing(Class::getName))
        .collect(Collectors.toList());
    return classes;
  }

  final File targetDirectory = new File("target");
  final File outputDirectory = new File(this.targetDirectory, "docs");
  final File sourcesDirectory = new File(this.outputDirectory, "sources");
  final File sourcesExamplesDirectory = new File(this.sourcesDirectory, "examples");
  final File sinksDirectory = new File(this.outputDirectory, "sinks");
  final File sinksExamplesDirectory = new File(this.sinksDirectory, "examples");
  final File transformationsDirectory = new File(this.outputDirectory, "transformations");
  final File transformationsExampleDirectory = new File(this.transformationsDirectory, "examples");
  final File convertersDirectory = new File(this.outputDirectory, "converters");
  final File converterExamplesDirectory = new File(this.convertersDirectory, "examples");
  final File configProvidersDirectory = new File(this.outputDirectory, "configProviders");
  final File configProviderExamplesDirectory = new File(this.configProvidersDirectory, "examples");


  Plugin plugin;

  static Map<Package, Plugin> pluginCache = new HashMap<>();

  @BeforeEach
  public void before() throws MalformedURLException {
    ObjectMapperFactory.INSTANCE.configure(SerializationFeature.INDENT_OUTPUT, true);

    Stream.of(
        this.targetDirectory,
        this.outputDirectory,
        this.sourcesDirectory,
        this.sourcesExamplesDirectory,
        this.sinksDirectory,
        this.sinksExamplesDirectory,
        this.transformationsDirectory,
        this.transformationsExampleDirectory,
        this.convertersDirectory,
        this.converterExamplesDirectory,
        this.configProvidersDirectory,
        this.configProviderExamplesDirectory
    )
        .filter(f -> !f.isDirectory())
        .forEach(File::mkdirs);

    log.info("before() - {}", this.getClass());
    Package pkg = this.getPackage();

    this.plugin = pluginCache.computeIfAbsent(pkg, aPackage -> {
      PluginLoader loader = new PluginLoader(aPackage);
      return loader.load();
    });
  }

  DynamicTest connectorRstTest(final File outputFile, Plugin.Configurable configurable, final String templateName, final boolean writeExamples) {
    return dynamicTest(configurable.getCls().getSimpleName(), () -> {
      write(outputFile, configurable, templateName);
    });
  }

  private void write(File outputFile, Object input, String templateName) throws IOException, TemplateException {
    Template template = configuration.getTemplate(templateName);
    log.info("Writing {}", outputFile);
    try (Writer writer = Files.newWriter(outputFile, Charsets.UTF_8)) {
      process(writer, template, input);
    }
  }

  @TestFactory
  public Stream<DynamicTest> rstSources() {
    final String templateName = "rst/source.rst.ftl";

    return this.plugin.getSourceConnectors()
        .stream()
        .map(sc -> connectorRstTest(
            new File(this.sourcesDirectory, sc.getCls().getSimpleName() + ".rst"),
            sc,
            templateName,
            true
            )
        );
  }

  @TestFactory
  public Stream<DynamicTest> rstSinks() {
    final String templateName = "rst/sink.rst.ftl";

    return this.plugin.getSinkConnectors()
        .stream()
        .map(sc -> connectorRstTest(
            outputRST(this.sinksDirectory, sc.getCls()),
            sc,
            templateName,
            true
            )
        );
  }

  @TestFactory
  public Stream<DynamicTest> rstTransformations() {
    final String templateName = "rst/transformation.rst.ftl";

    return this.plugin.getTransformations()
        .stream()
        .map(sc -> connectorRstTest(
            outputRST(this.transformationsDirectory, sc.getCls()),
            sc,
            templateName,
            true
            )
        );
  }

  @TestFactory
  public Stream<DynamicTest> rstConfigProviders() {
    final String templateName = "rst/configProvider.rst.ftl";

    return this.plugin.getConfigProviders()
        .stream()
        .map(sc -> connectorRstTest(
            outputRST(this.configProvidersDirectory, sc.getCls()),
            sc,
            templateName,
            true
            )
        );
  }

  @TestFactory
  public Stream<DynamicTest> rstConverters() {
    final String templateName = "rst/converter.rst.ftl";

    return this.plugin.getConverters()
        .stream()
        .map(sc -> connectorRstTest(
            outputRST(this.convertersDirectory, sc.getCls()),
            sc,
            templateName,
            true
            )
        );
  }

  void process(Writer writer, Template template, Object input) throws IOException, TemplateException {
    Map<String, Object> variables = ImmutableMap.of(
        "input", input
    );
    template.process(variables, writer);
  }

  void assertConfig(ConfigDef configDef, Map<String, String> config) {
    int errorCount = 0;
    List<ConfigValue> values = configDef.validate(config);
    for (ConfigValue value : values) {
      errorCount += value.errorMessages().size();
    }

    assertEquals(0, errorCount, () -> {
      StringBuilder builder = new StringBuilder();
      builder.append("Example validation was not successful.");
      builder.append('\n');

      for (ConfigValue value : values) {
        for (String s : value.errorMessages()) {
          builder.append(value.name());
          builder.append(": ");
          builder.append(s);
          builder.append('\n');
        }
      }
      return builder.toString();
    });
  }

  @TestFactory
  public Stream<DynamicTest> validateSinkConnectorExamples() {
    Map<File, Plugin.SinkConnector> sinkConnectorExamples = examples(this.plugin.getSinkConnectors());


    return sinkConnectorExamples.entrySet().stream()
        .map(e -> dynamicTest(String.format("%s/%s", e.getValue().getCls().getSimpleName(), e.getKey().getName()), () -> {
          final Plugin.SinkConnectorExample example = loadExample(e, Plugin.SinkConnectorExample.class);
          final Plugin.SinkConnector sinkConnector = e.getValue();
          final File rstOutputFile = outputRST(
              this.sinksExamplesDirectory,
              sinkConnector.getCls(),
              e.getKey()
          );

          assertConfig(sinkConnector.getConfiguration().getConfigDef(), example.getConfig());
          ImmutableSinkConnectorExampleInput.Builder builder = ImmutableSinkConnectorExampleInput.builder();
          builder.example(example);
          String config = connectorConfig(sinkConnector, example);
          builder.config(config);
          if (null != example.getInput()) {
            builder.inputJson(writeValueAsIndentedString(example.getInput()));
          }
          if (null != example.getOutput()) {
            builder.outputJson(writeValueAsIndentedString(example.getOutput()));
          }
          write(rstOutputFile, builder.build(), "rst/sinkConnectorExample.rst.ftl");
        }));
  }

  @TestFactory
  public Stream<DynamicTest> validateSourceConnectorExamples() {
    Map<File, Plugin.SourceConnector> sourceConnectorExamples = examples(this.plugin.getSourceConnectors());

    return sourceConnectorExamples.entrySet().stream()
        .map(e -> dynamicTest(String.format("%s/%s", e.getValue().getCls().getSimpleName(), e.getKey().getName()), () -> {
          final Plugin.SourceConnectorExample example = loadExample(e, Plugin.SourceConnectorExample.class);
          final Plugin.SourceConnector sourceConnector = e.getValue();
          final File rstOutputFile = outputRST(
              this.sourcesExamplesDirectory,
              sourceConnector.getCls(),
              e.getKey()
          );
          assertConfig(sourceConnector.getConfiguration().getConfigDef(), example.getConfig());
          ImmutableSourceConnectorExampleInput.Builder builder = ImmutableSourceConnectorExampleInput.builder();
          builder.example(example);
          String config = connectorConfig(sourceConnector, example);
          builder.config(config);

          if (null != example.getOutput()) {
            builder.outputJson(writeValueAsIndentedString(example.getOutput()));
          }

          write(rstOutputFile, builder.build(), "rst/sourceConnectorExample.rst.ftl");
        }));
  }

  @TestFactory
  public Stream<DynamicTest> validateTransformationExamples() {
    Map<File, Plugin.Transformation> transformationExamples = examples(this.plugin.getTransformations());

    return transformationExamples.entrySet().stream()
        .map(e -> dynamicTest(String.format("%s/%s", e.getValue().getCls().getSimpleName(), e.getKey().getName()), () -> {
          final Plugin.Transformation transformation = e.getValue();
          final File rstOutputFile = outputRST(
              this.transformationsExampleDirectory,
              transformation.getCls(),
              e.getKey()
          );
          final Plugin.TransformationExample example = loadExample(e, Plugin.TransformationExample.class);

          final Class<? extends Transformation> transformationClass;

          if (Strings.isNullOrEmpty(example.getChildClass())) {
            transformationClass = transformation.getCls();
          } else {
            Optional<Class> childClass = Arrays.stream(transformation.getCls().getClasses())
                .filter(c -> example.getChildClass().equals(c.getSimpleName()))
                .findFirst();
            assertTrue(
                childClass.isPresent(),
                String.format(
                    "Could not find child '%s' of class '%s'",
                    example.getChildClass(),
                    transformation.getCls().getName()
                )
            );
            transformationClass = childClass.get();
          }

          Transformation<SinkRecord> transform = transformationClass.newInstance();
          transform.configure(example.getConfig());

          ImmutableTransformationExampleInput.Builder builder = ImmutableTransformationExampleInput.builder();
          builder.example(example);

          if (null != example.getConfig() && !example.getConfig().isEmpty()) {
            String transformKey = CaseFormat.UPPER_CAMEL.to(
                CaseFormat.LOWER_CAMEL,
                transformation.getCls().getSimpleName()
            );
            String transformPrefix = "transforms." + transformKey + ".";
            LinkedHashMap<String, String> config = new LinkedHashMap<>();
            config.put("transforms", transformKey);
            config.put(transformPrefix + "type", transformationClass.getName());

            for (Map.Entry<String, String> a : example.getConfig().entrySet()) {
              config.put(transformPrefix + a.getKey(), a.getValue());
            }

            String configJson = writeValueAsIndentedString(config);
            builder.config(configJson);
          }

          if (null != example.getInput()) {
            String inputJson = writeValueAsIndentedString(example.getInput());
            builder.inputJson(inputJson);

            SinkRecord output = transform.apply(example.getInput());
            if (null != output) {
              String outputJson = writeValueAsIndentedString(output);
              builder.outputJson(outputJson);

              final List<String> inputLines = Arrays.asList(inputJson.split("\\r?\\n"));
              final List<String> outputLines = Arrays.asList(outputJson.split("\\r?\\n"));


              Patch<String> patch = DiffUtils.diff(inputLines, outputLines);
              for (AbstractDelta<String> delta : patch.getDeltas()) {
                log.trace("delta: start={} end={}", delta.getTarget().getPosition(), delta.getTarget().last());
                final int lineStart = delta.getTarget().getPosition() + 1;
                final int lineEnd = lineStart + delta.getTarget().size() - 1;
                for (int i = lineStart; i <= lineEnd; i++) {
                  builder.addOutputEmphasizeLines(i);
                }
              }
            }
          }

          try (Writer writer = Files.newWriter(rstOutputFile, Charsets.UTF_8)) {
            Template template = configuration.getTemplate("rst/transformationExample.rst.ftl");
            process(writer, template, builder.build());
          }
        }));
  }


  void converterConfig(
      Plugin.Converter converter,
      Plugin.ConverterExample example,
      ImmutableConverterExampleInput.Builder builder) throws IOException {

    List<String> converters = Arrays.asList("key.converter", "value.converter");

    for (String prefix : converters) {
      Map<String, String> config = new LinkedHashMap<>();
      config.put(prefix, converter.getCls().getName());
      for (Map.Entry<String, String> e : example.getConfig().entrySet()) {
        config.put(prefix + "." + e.getKey(), e.getValue());
      }

      String connectorConfig = writeValueAsIndentedString(config);
      Properties properties = new Properties();
      properties.putAll(config);
      String workerConfig = writeAsIndentedString(properties);

      if (prefix.equals("key.converter")) {
        builder.connectorKeyConfig(connectorConfig);
        builder.workerKeyConfig(workerConfig);
      } else {
        builder.connectorValueConfig(connectorConfig);
        builder.workerValueConfig(workerConfig);
      }
    }
  }

  void configProviderConfig(
      Plugin.ConfigProvider converter,
      Plugin.ConfigProviderExample example,
      ImmutableConfigProviderExampleInput.Builder builder) throws IOException {

    Properties workerConfig = new Properties();
    workerConfig.put("config.providers", example.getPrefix());
    String workerConfigPrefix = String.join(".",
        "config", "providers", example.getPrefix()
    );
    workerConfig.put(workerConfigPrefix + ".class", converter.getCls().getName());
    for (Map.Entry<String, String> kvp : example.getConfig().entrySet()) {
      workerConfig.put(workerConfigPrefix + ".param." + kvp.getKey(), kvp.getValue());
    }
    builder.config(writeAsIndentedString(workerConfig));
    builder.connectorConfig(writeValueAsIndentedString(example.getConnectorConfig()));
  }

  @TestFactory
  public Stream<DynamicTest> validateConverterExamples() {
    Map<File, Plugin.Converter> transformationExamples = examples(this.plugin.getConverters());

    return transformationExamples.entrySet().stream()
        .map(e -> dynamicTest(String.format("%s/%s", e.getValue().getCls().getSimpleName(), e.getKey().getName()), () -> {
          final Plugin.Converter converter = e.getValue();
          final File rstOutputFile = outputRST(
              this.converterExamplesDirectory,
              converter.getCls(),
              e.getKey()
          );
          final Plugin.ConverterExample example = loadExample(e, Plugin.ConverterExample.class);
          assertNotNull(converter.getConfiguration(), "Converter does not define configuration");
          assertConfig(converter.getConfiguration().getConfigDef(), example.getConfig());
          ImmutableConverterExampleInput.Builder builder = ImmutableConverterExampleInput.builder();
          builder.example(example);
          converterConfig(converter, example, builder);

          write(rstOutputFile, builder.build(), "rst/converterExample.rst.ftl");
        }));
  }

  @TestFactory
  public Stream<DynamicTest> validateConfigProviderExamples() {
    Map<File, Plugin.ConfigProvider> transformationExamples = examples(this.plugin.getConfigProviders());

    return transformationExamples.entrySet().stream()
        .map(e -> dynamicTest(String.format("%s/%s", e.getValue().getCls().getSimpleName(), e.getKey().getName()), () -> {
          final Plugin.ConfigProvider configProvider = e.getValue();
          final File rstOutputFile = outputRST(
              this.configProviderExamplesDirectory,
              configProvider.getCls(),
              e.getKey()
          );
          final Plugin.ConfigProviderExample example = loadExample(e, Plugin.ConfigProviderExample.class);
          assertNotNull(configProvider.getConfiguration(), "ConfigProvider does not define configuration");
          assertConfig(configProvider.getConfiguration().getConfigDef(), example.getConfig());
          ImmutableConfigProviderExampleInput.Builder builder = ImmutableConfigProviderExampleInput.builder();
          builder.example(example);
          configProviderConfig(configProvider, example, builder);
          write(rstOutputFile, builder.build(), "rst/configProviderExample.rst.ftl");
        }));
  }

  private String writeAsIndentedString(Properties properties) throws IOException {
    String result;
    try (StringWriter writer = new StringWriter()) {
      properties.store(writer, "");
      result = writer.toString();
    }
    return result.replaceAll("(?m)^", "    ");
  }

  private String writeValueAsIndentedString(Object o) throws JsonProcessingException {
    String result = ObjectMapperFactory.INSTANCE.writeValueAsString(o);
    return result.replaceAll("(?m)^", "    ");
  }

  private String connectorConfig(Plugin.Connector connector, Plugin.ConnectorExample example) throws JsonProcessingException {
    ObjectNode config = ObjectMapperFactory.INSTANCE.createObjectNode();
    config.put("connector.class", connector.getCls().getName());
    if (connector instanceof Plugin.SinkConnector) {
      config.put("topic", "<required setting>");
    }
    for (Map.Entry<String, String> e : example.getConfig().entrySet()) {
      config.put(e.getKey(), e.getValue());
    }

    if (null != example.transformations() && !example.transformations().isEmpty()) {
      config.put("transforms", Joiner.on(',').join(example.transformations().keySet()));
      for (Map.Entry<String, Map<String, String>> transform : example.transformations().entrySet()) {
        assertTrue(
            transform.getValue().containsKey("type"),
            String.format("Transform '%s' does not have a type property.", transform.getKey())
        );

        for (Map.Entry<String, String> entry : transform.getValue().entrySet()) {
          String key = String.format("transforms.%s.%s", transform.getKey(), entry.getKey());
          config.put(key, entry.getValue());
        }
      }
    }

    return writeValueAsIndentedString(config);
  }

  <T extends Plugin.Configurable> Map<File, T> examples(List<T> input) {
    Map<File, T> result = new LinkedHashMap<>();
    for (T configurable : input) {
      for (String example : configurable.getExamples()) {
        result.put(new File(example), configurable);
      }
    }
    return result;
  }

  private <T> T loadExample(Map.Entry<File, ?> e, Class<T> cls) throws IOException {
    log.info("loadExample() - file = '{}'", e.getKey().getAbsolutePath());
    try (InputStream inputStream = this.getClass().getResourceAsStream(e.getKey().getAbsolutePath())) {
      return ObjectMapperFactory.INSTANCE.readValue(inputStream, cls);
    }
  }

  private File outputRST(File parentDirectory, Class<?> cls) {
    return new File(parentDirectory, cls.getSimpleName() + ".rst");
  }

  private File outputRST(File parentDirectory, Class<?> cls, File exampleFile) {
    return new File(
        parentDirectory,
        String.format(
            "%s.%s.rst",
            cls.getSimpleName(),
            Files.getNameWithoutExtension(exampleFile.getName())
        )
    );
  }


  Plugin.SchemaInput buildSchemaInput(Schema schema) {
    return buildSchemaInput(schema, null);
  }

  Plugin.SchemaInput buildSchemaInput(Schema schema, String fieldName) {
    ImmutableSchemaInput.Builder schemaInput = ImmutableSchemaInput.builder()
        .name(schema.name())
        .doc(schema.doc())
        .type(schema.type())
        .fieldName(fieldName)
        .isOptional(schema.isOptional());

    if (Schema.Type.STRUCT == schema.type()) {
      for (Field field : schema.fields()) {
        Plugin.SchemaInput fieldSchema = buildSchemaInput(field.schema(), field.name());
        schemaInput.addFields(fieldSchema);
      }
    } else if (Schema.Type.MAP == schema.type()) {
      schemaInput.key(buildSchemaInput(schema.keySchema()));
      schemaInput.value(buildSchemaInput(schema.valueSchema()));
    } else if (Schema.Type.ARRAY == schema.type()) {
      schemaInput.value(buildSchemaInput(schema.valueSchema()));
    }

    return schemaInput.build();
  }

  void findAllSchemas(Set<Schema> schemas, Schema schema) {
    schemas.add(schema);
    if (Schema.Type.STRUCT == schema.type()) {
      for (Field field : schema.fields()) {
        findAllSchemas(schemas, field.schema());
      }
    } else if (Schema.Type.MAP == schema.type()) {
      findAllSchemas(schemas, schema.keySchema());
      findAllSchemas(schemas, schema.valueSchema());
    } else if (Schema.Type.ARRAY == schema.type()) {
      findAllSchemas(schemas, schema.valueSchema());
    }
  }

  @TestFactory
  public Stream<DynamicTest> rstSchemas() throws IOException, TemplateException {
    final File parentDirectory = new File(outputDirectory, "schemas");
    final List<Schema> schemas = schemas();
    final Set<Schema> allSchemas = new LinkedHashSet<>();
    for (Schema s : schemas) {
      findAllSchemas(allSchemas, s);
    }


    if (!schemas.isEmpty()) {
      if (!parentDirectory.exists()) {
        parentDirectory.mkdirs();
      }
      final File outputFile = new File(outputDirectory, "schemas.rst");
      final String templateName = "rst/schemas.rst.ftl";
      Template template = configuration.getTemplate(templateName);
      try (Writer writer = Files.newWriter(outputFile, Charsets.UTF_8)) {
        process(writer, template, this.plugin);
      }
    }

    final String templateName = "rst/schema.rst.ftl";
    return allSchemas.stream()
        .filter(schema -> !Strings.isNullOrEmpty(schema.name()))
        .map(schema -> dynamicTest(String.format("%s.%s", schema.type(), schema.name()), () -> {
          Plugin.SchemaInput schemaInput = buildSchemaInput(schema);
          StringBuilder filenameBuilder = new StringBuilder()
              .append(schema.type().toString().toLowerCase());
          if (!Strings.isNullOrEmpty(schema.name())) {
            filenameBuilder.append('.').append(schema.name());
          }
          filenameBuilder.append(".rst");
          File outputFile = new File(parentDirectory, filenameBuilder.toString());
          Template template = configuration.getTemplate(templateName);
          log.info("Writing {}", outputFile);


          try (Writer writer = Files.newWriter(outputFile, Charsets.UTF_8)) {
            Map<String, Object> variables = ImmutableMap.of(
                "input", schemaInput
            );
            template.process(variables, writer);
          }
        }));
  }

  @Test
  public void rstIndex() throws IOException, TemplateException {
    final File outputFile = new File(this.outputDirectory, "index.rst");
    final String templateName = "rst/index.rst.ftl";

    Template template = configuration.getTemplate(templateName);
    try (Writer writer = Files.newWriter(outputFile, Charsets.UTF_8)) {
      process(writer, template, this.plugin);
    }
  }

  @Test
  public void readmeMD() throws IOException, TemplateException {
    final File outputFile = new File("target", "README.md");
    Template template = configuration.getTemplate("md/README.md.ftl");
    try (Writer writer = Files.newWriter(outputFile, Charsets.UTF_8)) {
      process(writer, template, this.plugin);
    }
  }
}
