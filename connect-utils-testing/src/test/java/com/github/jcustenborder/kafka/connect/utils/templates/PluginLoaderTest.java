package com.github.jcustenborder.kafka.connect.utils.templates;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.jcustenborder.kafka.connect.utils.TestKeyAndValueTransformation;
import com.github.jcustenborder.kafka.connect.utils.TestSinkConnector;
import com.github.jcustenborder.kafka.connect.utils.TestSourceConnector;
import com.github.jcustenborder.kafka.connect.utils.TestTransformation;
import com.github.jcustenborder.kafka.connect.utils.ToUpperCase;
import com.github.jcustenborder.kafka.connect.utils.jackson.ObjectMapperFactory;
import com.github.jcustenborder.kafka.connect.utils.nodoc.NoDocTestSinkConnector;
import com.github.jcustenborder.kafka.connect.utils.nodoc.NoDocTestSourceConnector;
import com.github.jcustenborder.kafka.connect.utils.nodoc.NoDocTestTransformation;
import com.google.common.collect.ImmutableSet;
import org.apache.kafka.connect.sink.SinkConnector;
import org.apache.kafka.connect.source.SourceConnector;
import org.apache.kafka.connect.transforms.Transformation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

import static com.github.jcustenborder.kafka.connect.utils.SinkRecordHelper.write;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class PluginLoaderTest {
  private static final Logger log = LoggerFactory.getLogger(PluginLoaderTest.class);
  PluginLoader pluginLoader = new PluginLoader(TestTransformation.class.getPackage());

  @Test
  public void findTransformations() {
    final Set<Class<? extends Transformation>> expected = ImmutableSet.of(
        NoDocTestTransformation.class,
        TestTransformation.class,
        ToUpperCase.class,
        TestKeyAndValueTransformation.class
    );
    final Set<Class<? extends Transformation>> actual = pluginLoader.findTransformations();

    assertEquals(expected, actual);
  }

  @Test
  public void findSinkConnectors() {
    final Set<Class<? extends SinkConnector>> expected = ImmutableSet.of(
        TestSinkConnector.class,
        NoDocTestSinkConnector.class
    );
    final Set<Class<? extends SinkConnector>> actual = pluginLoader.findSinkConnectors();

    assertEquals(expected, actual);
  }

  @Test
  public void findSourceConnectors() {
    final Set<Class<? extends SourceConnector>> expected = ImmutableSet.of(
        TestSourceConnector.class,
        NoDocTestSourceConnector.class
    );
    final Set<Class<? extends SourceConnector>> actual = pluginLoader.findSourceConnectors();

    assertEquals(expected, actual);
  }

  @Test
  public void load() {
    Plugin plugin = pluginLoader.load();
  }

  @BeforeAll
  public static void beforeAll() {
    ObjectMapperFactory.INSTANCE.configure(SerializationFeature.INDENT_OUTPUT, true);
  }
}
