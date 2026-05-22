package org.greenrobot.greendao.gradle

import org.gradle.api.Project
import org.junit.Test

import static org.junit.Assert.assertEquals
import static org.mockito.Matchers.any
import static org.mockito.Mockito.mock
import static org.mockito.Mockito.when

class GreendaoOptionsTest {
    @Test
    void testSchemaOptions() {
        def project = mock(Project)
        when(project.file(any(String))).thenReturn(mock(File))

        def options = new GreendaoOptions(project)
        options.with {
            schemas {
                notes
                orders {
                    version 2
                }
            }
        }

        assertEquals(["notes", "orders"].toSet(), options.schemas.schemasMap.keySet())
        assertEquals(2, options.schemas.orders.version)
        assertEquals(null, options.schemas.notes.version)
    }
}
