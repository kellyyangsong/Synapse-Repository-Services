package org.sagebionetworks.table.query;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sagebionetworks.table.query.model.SortSpecification;
import org.sagebionetworks.table.query.model.SortSpecificationList;
import org.sagebionetworks.table.query.util.SqlElementUntils;

public class SortSpecificationListTest {

	@Test
	public void testToSQLOne() throws ParseException {
		List<SortSpecification> list = SqlElementUntils.createSortSpecifications("foo asc");
		SortSpecificationList element = new SortSpecificationList(list);
		assertEquals("foo ASC", element.toString());
	}

	@Test
	public void testToSQLMore() throws ParseException {
		List<SortSpecification> list = SqlElementUntils.createSortSpecifications("foo asc", "bar desc");
		SortSpecificationList element = new SortSpecificationList(list);
		assertEquals("foo ASC, bar DESC", element.toString());
	}

	@Test
	public void testToSQLMoreNoOrderSpec() throws ParseException {
		List<SortSpecification> list = SqlElementUntils.createSortSpecifications("foo", "bar");
		SortSpecificationList element = new SortSpecificationList(list);
		assertEquals("foo, bar", element.toString());
	}

	@Test
	public void testGetChildren() throws ParseException {
		SortSpecificationList element = new TableQueryParser("foo asc, bar des").sortSpecificationList();
		assertEquals(new LinkedList<>(element.getSortSpecifications()), element.getChildren());
	}
}
