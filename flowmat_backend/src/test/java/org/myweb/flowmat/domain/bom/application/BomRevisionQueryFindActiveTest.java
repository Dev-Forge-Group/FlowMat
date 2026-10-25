package org.myweb.flowmat.domain.bom.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.bom.application.publicapi.BomRevisionView;
import org.myweb.flowmat.domain.bom.domain.entity.BomHeader;
import org.myweb.flowmat.domain.bom.repository.BomHeaderRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;

/** Another context reads a BOM revision as its view (docs/architecture/adr/ADR-018-entity-boundary.md, 2go). */
class BomRevisionQueryFindActiveTest {
    private final BomHeaderRepository headers = mock(BomHeaderRepository.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final BomRevisionQueryImpl query = new BomRevisionQueryImpl(headers, access, mock(BomRevisionLock.class), mock(EntityManager.class));

    @Test
    void aRevisionIsReadAsItsViewAndTheCallerChecksAccess() {
        BomHeader row = new BomHeader();
        row.setBomId("bom-1");
        row.setProjectId("prj-1");
        row.setTargetItemId("item-1");
        row.setBomName("Bread");
        row.setBomVersion(3);
        row.setBomStatus("retired");
        when(headers.findByBomIdAndDeletedYn("bom-1", "N")).thenReturn(Optional.of(row));

        assertThat(query.findActive("bom-1")).contains(new BomRevisionView("bom-1", "prj-1", "item-1", "Bread", 3, "retired"));
        verifyNoInteractions(access);
    }

    @Test
    void aMissingOrDeletedRevisionIsEmpty() {
        when(headers.findByBomIdAndDeletedYn("gone", "N")).thenReturn(Optional.empty());

        assertThat(query.findActive("gone")).isEmpty();
    }
}
