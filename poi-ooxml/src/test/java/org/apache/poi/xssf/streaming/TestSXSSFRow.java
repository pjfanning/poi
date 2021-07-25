/*
 *  ====================================================================
 *    Licensed to the Apache Software Foundation (ASF) under one or more
 *    contributor license agreements.  See the NOTICE file distributed with
 *    this work for additional information regarding copyright ownership.
 *    The ASF licenses this file to You under the Apache License, Version 2.0
 *    (the "License"); you may not use this file except in compliance with
 *    the License.  You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 * ====================================================================
 */

package org.apache.poi.xssf.streaming;

import org.apache.poi.ss.tests.usermodel.BaseTestXRow;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellCopyPolicy;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.SXSSFITestDataProvider;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests for XSSFRow
 */
public final class TestSXSSFRow extends BaseTestXRow {

    public TestSXSSFRow() {
        super(SXSSFITestDataProvider.instance);
    }


    @AfterEach
    void tearDown() {
        ((SXSSFITestDataProvider) _testDataProvider).cleanup();
    }

    @Override
    @Disabled("see <https://bz.apache.org/bugzilla/show_bug.cgi?id=62030#c1>")
    protected void testCellShiftingRight(){
        // Remove when SXSSFRow.shiftCellsRight() is implemented.
    }

    @Override
    @Disabled("see <https://bz.apache.org/bugzilla/show_bug.cgi?id=62030#c1>")
    protected void testCellShiftingLeft(){
        // Remove when SXSSFRow.shiftCellsLeft() is implemented.
    }

    @Test
    void testCopyRowFromExternalSheet() throws IOException {
        final SXSSFWorkbook workbook = new SXSSFWorkbook();
        final SXSSFSheet srcSheet = workbook.createSheet("src");
        final SXSSFSheet destSheet = workbook.createSheet("dest");
        workbook.createSheet("other");

        final Row srcRow = srcSheet.createRow(0);
        int col = 0;
        //Test 2D and 3D Ref Ptgs (Pxg for OOXML Workbooks)
        srcRow.createCell(col++).setCellFormula("B5");
        srcRow.createCell(col++).setCellFormula("src!B5");
        srcRow.createCell(col++).setCellFormula("dest!B5");
        srcRow.createCell(col++).setCellFormula("other!B5");

        //Test 2D and 3D Ref Ptgs with absolute row
        srcRow.createCell(col++).setCellFormula("B$5");
        srcRow.createCell(col++).setCellFormula("src!B$5");
        srcRow.createCell(col++).setCellFormula("dest!B$5");
        srcRow.createCell(col++).setCellFormula("other!B$5");

        //Test 2D and 3D Area Ptgs (Pxg for OOXML Workbooks)
        srcRow.createCell(col++).setCellFormula("SUM(B5:D$5)");
        srcRow.createCell(col++).setCellFormula("SUM(src!B5:D$5)");
        srcRow.createCell(col++).setCellFormula("SUM(dest!B5:D$5)");
        srcRow.createCell(col++).setCellFormula("SUM(other!B5:D$5)");

        //////////////////

        final int styleCount = workbook.getNumCellStyles();

        final SXSSFRow destRow = destSheet.createRow(1);
        destRow.copyRowFrom(srcRow, new CellCopyPolicy());

        //////////////////

        //Test 2D and 3D Ref Ptgs (Pxg for OOXML Workbooks)
        col = 0;
        Cell cell = destRow.getCell(col++);
        assertNotNull(cell);
        assertEquals("B6", cell.getCellFormula(), "RefPtg");

        cell = destRow.getCell(col++);
        assertNotNull(cell);
        assertEquals("src!B6", cell.getCellFormula(), "Ref3DPtg");

        cell = destRow.getCell(col++);
        assertNotNull(cell);
        assertEquals("dest!B6", cell.getCellFormula(), "Ref3DPtg");

        cell = destRow.getCell(col++);
        assertNotNull(cell);
        assertEquals("other!B6", cell.getCellFormula(), "Ref3DPtg");

        /////////////////////////////////////////////

        //Test 2D and 3D Ref Ptgs with absolute row (Ptg row number shouldn't change)
        cell = destRow.getCell(col++);
        assertNotNull(cell);
        assertEquals("B$5", cell.getCellFormula(), "RefPtg");

        cell = destRow.getCell(col++);
        assertNotNull(cell);
        assertEquals("src!B$5", cell.getCellFormula(), "Ref3DPtg");

        cell = destRow.getCell(col++);
        assertNotNull(cell);
        assertEquals("dest!B$5", cell.getCellFormula(), "Ref3DPtg");

        cell = destRow.getCell(col++);
        assertNotNull(cell);
        assertEquals("other!B$5", cell.getCellFormula(), "Ref3DPtg");

        //////////////////////////////////////////

        //Test 2D and 3D Area Ptgs (Pxg for OOXML Workbooks)
        // Note: absolute row changes from last cell to first cell in order
        // to maintain topLeft:bottomRight order
        cell = destRow.getCell(col++);
        assertNotNull(cell);
        assertEquals("SUM(B$5:D6)", cell.getCellFormula(), "Area2DPtg");

        cell = destRow.getCell(col++);
        assertNotNull(cell);
        assertEquals("SUM(src!B$5:D6)", cell.getCellFormula(), "Area3DPtg");

        cell = destRow.getCell(col++);
        assertNotNull(cell);
        assertEquals("SUM(dest!B$5:D6)", cell.getCellFormula(), "Area3DPtg");

        cell = destRow.getCell(col++);
        assertNotNull(cell);
        assertEquals("SUM(other!B$5:D6)", cell.getCellFormula(), "Area3DPtg");

        assertEquals(styleCount, workbook.getNumCellStyles(), "no new styles should be added by copyRow");
        workbook.close();
    }

}
