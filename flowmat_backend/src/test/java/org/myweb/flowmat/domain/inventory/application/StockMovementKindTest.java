package org.myweb.flowmat.domain.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockMovementCommand.Kind;
import org.myweb.flowmat.domain.inventory.domain.enums.InventoryTransactionType;

/** {@link InventoryCommandService} applies another context's movement as the transaction type of the same name. */
class StockMovementKindTest {

    @Test
    void everyKindIsAnInventoryTransactionType() {
        assertThat(Arrays.stream(Kind.values()).map(kind -> InventoryTransactionType.valueOf(kind.name())))
            .containsExactly(InventoryTransactionType.RESERVE, InventoryTransactionType.RELEASE,
                InventoryTransactionType.PRODUCTION_INPUT, InventoryTransactionType.PRODUCTION_OUTPUT,
                InventoryTransactionType.ADJUSTMENT, InventoryTransactionType.QUARANTINE);
    }
}
