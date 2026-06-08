package no.entur.papsukkal.validation;

import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLStreamException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NetexDatasetInspectorTest {

    private final NetexDatasetInspector inspector = new NetexDatasetInspector();

    private InputStream fixture(String name) {
        InputStream in = getClass().getResourceAsStream("/fixtures/" + name);
        if (in == null) {
            throw new IllegalStateException("missing fixture: " + name);
        }
        return in;
    }

    @Test
    void counts_zones_groups_and_members() throws Exception {
        DatasetCounts counts = inspector.inspect(fixture("valid-farezones.xml"));

        assertThat(counts.fareZoneCount()).isEqualTo(10);
        assertThat(counts.groupCount()).isEqualTo(2);
        assertThat(counts.memberCount()).isEqualTo(5);
        assertThat(counts.unresolvedRefs()).isEmpty();
    }

    @Test
    void flags_member_refs_that_do_not_resolve() throws Exception {
        DatasetCounts counts = inspector.inspect(fixture("dangling-ref-farezones.xml"));

        assertThat(counts.fareZoneCount()).isEqualTo(5);
        assertThat(counts.groupCount()).isEqualTo(2);
        assertThat(counts.memberCount()).isEqualTo(5);
        assertThat(counts.unresolvedRefs()).containsExactly("ENT:FareZone:99");
    }

    @Test
    void throws_on_malformed_xml() {
        assertThatThrownBy(() -> inspector.inspect(fixture("malformed.xml")))
                .isInstanceOf(XMLStreamException.class);
    }

    @Test
    void rejects_dtd_and_entities_to_lock_in_xxe_hardening() {
        // DTDs and external entities are disabled (SUPPORT_DTD / IS_SUPPORTING_EXTERNAL_ENTITIES =
        // false) because the input is an untrusted external GCS export. This regression test fails
        // if a maintainer re-enables DTD processing (reopening the XXE / entity-expansion vector).
        String withDoctype = "<?xml version=\"1.0\"?>\n"
                + "<!DOCTYPE PublicationDelivery [<!ENTITY xxe \"INJECTED\">]>\n"
                + "<PublicationDelivery><FareZone id=\"ENT:FareZone:&xxe;\"/></PublicationDelivery>";

        assertThatThrownBy(() -> inspector.inspect(
                new ByteArrayInputStream(withDoctype.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(XMLStreamException.class);
    }
}
