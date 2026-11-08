package org.myweb.flowmat.domain.bom.application.publicapi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * BOM revisions and their material requirements for other bounded contexts, which use this instead of the BOM service
 * (docs/architecture/adr/ADR-036-cross-context-contract-types.md). The BOM API returns the same types.
 */
public interface BomQuery {

    /** The project's revisions, of one target item when {@code targetItemId} is given. Checks project read access. */
    List<BomView> listBoms(String projectId, String targetItemId);

    /** Checks project read access. */
    BomView getBom(String bomId);

    /** Requirements for a production quantity of any revision, phantoms through today's revisions. Checks project read access. */
    BomRequirement calculateRequirements(String bomId, BigDecimal productionQuantity);

    /**
     * Requirements for a production run, frozen at start: the BOM must be approved, in the project, and for the
     * run's target item. Access checks are the caller's job.
     */
    BomRequirement requirementsForRun(String bomId, String projectId, String targetItemId, BigDecimal productionQuantity);

    /**
     * As above, with phantom lines expanded through the phantom's revision effective on {@code phantomDay}, a project
     * calendar day; null means the project's today (docs/domain/multi-level-bom.md P2-P3).
     */
    BomRequirement requirementsForRun(String bomId, String projectId, String targetItemId, BigDecimal productionQuantity,
                                      LocalDate phantomDay);

    /**
     * As above, with the optional materials a work order picked (ADR-033): an optional line whose item is one of
     * {@code optionalItemIds} is planned, here and inside phantoms; any other optional line is only listed in
     * {@code optionals}. The other overloads pick none.
     */
    BomRequirement requirementsForRun(String bomId, String projectId, String targetItemId, BigDecimal productionQuantity,
                                      LocalDate phantomDay, Collection<String> optionalItemIds);
}
