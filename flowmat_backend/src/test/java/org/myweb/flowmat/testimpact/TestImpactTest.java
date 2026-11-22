package org.myweb.flowmat.testimpact;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;

/** What TestImpact reads must match what JaCoCo writes, and what counts as touching every test. */
class TestImpactTest {

    @Test
    void readsTheClassesWithAnExecutedProbeFromExecutionData() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(0x01);
        out.writeChar(0xC0C0);
        out.writeChar(0x1007);
        out.writeByte(0x10);
        out.writeUTF("session");
        out.writeLong(1L);
        out.writeLong(2L);
        classBlock(out, "org/myweb/flowmat/Ran", new boolean[] {false, true, false});
        classBlock(out, "org/myweb/flowmat/LoadedOnly", new boolean[] {false, false});
        boolean[] many = new boolean[200];
        many[199] = true;
        classBlock(out, "org/myweb/flowmat/ManyProbes", many);

        assertThat(TestImpact.executedClasses(bytes.toByteArray()))
            .containsExactly("org/myweb/flowmat/ManyProbes", "org/myweb/flowmat/Ran");
    }

    @Test
    void readsWhatTheJacocoAgentOfThisRunWrites() throws IOException {
        byte[] exec = TestImpact.dump(false);
        // Gradle runs the tests with the agent (jacoco plugin); an IDE run without it has nothing to read.
        if (System.getProperty("org.gradle.test.worker") == null && exec == null) return;
        assertThat(exec).isNotNull();
        assertThat(TestImpact.executedClasses(exec)).contains("org/myweb/flowmat/testimpact/TestImpactTest");
    }

    @Test
    void springConfigurationFiltersAndMappingsTouchEveryTestAndAServiceDoesNot() {
        assertThat(TestImpactFilter.isHook("org/myweb/flowmat/global/websocket/WebSocketConfig")).isTrue();
        assertThat(TestImpactFilter.isHook("org/myweb/flowmat/Application")).isTrue();
        assertThat(TestImpactFilter.isHook("org/myweb/flowmat/global/logging/RequestCorrelationFilter")).isTrue();
        assertThat(TestImpactFilter.isHook("org/myweb/flowmat/domain/bom/domain/entity/BomLine")).isTrue();
        assertThat(TestImpactFilter.isHook("org/myweb/flowmat/testimpact/TestImpact")).isTrue();
        assertThat(TestImpactFilter.isHook("org/myweb/flowmat/does/not/Exist")).isTrue();
        assertThat(TestImpactFilter.isHook("org/myweb/flowmat/domain/bom/application/BomLineImportService")).isFalse();
    }

    @Test
    void hashesAClassFileAndSaysWhenItIsGone() {
        assertThat(TestImpact.hashOfClass("org/myweb/flowmat/testimpact/TestImpactTest")).hasSize(64);
        assertThat(TestImpact.hashOfClass("org/myweb/flowmat/does/not/Exist")).isEqualTo("missing");
    }

    private static void classBlock(DataOutputStream out, String name, boolean[] probes) throws IOException {
        out.writeByte(0x11);
        out.writeLong(name.hashCode());
        out.writeUTF(name);
        writeVarInt(out, probes.length);
        int buffer = 0;
        int count = 0;
        for (boolean probe : probes) {
            if (probe) buffer |= 1 << count;
            if (++count == 8) {
                out.writeByte(buffer);
                buffer = 0;
                count = 0;
            }
        }
        if (count > 0) out.writeByte(buffer);
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        if ((value & 0xFFFFFF80) == 0) {
            out.writeByte(value);
        } else {
            out.writeByte(0x80 | (value & 0x7F));
            writeVarInt(out, value >>> 7);
        }
    }
}
