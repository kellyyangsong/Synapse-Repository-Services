package org.sagebionetworks.table.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sagebionetworks.table.query.model.ColumnReference;
import org.sagebionetworks.table.query.model.EscapeCharacter;
import org.sagebionetworks.table.query.model.LikePredicate;
import org.sagebionetworks.table.query.model.Pattern;
import org.sagebionetworks.table.query.model.Predicate;
import org.sagebionetworks.table.query.model.UnsignedLiteral;
import org.sagebionetworks.table.query.util.SqlElementUntils;

import com.google.common.collect.Lists;

public class LikePredicateTest {

	@Test
	public void testLikePredicateToSQL() throws ParseException {
		ColumnReference columnReferenceLHS = SqlElementUntils.createColumnReference("foo");
		Boolean not = null;
		Pattern pattern = SqlElementUntils.createPattern("'%middle%'");
		EscapeCharacter escapeCharacter = null;
		LikePredicate element = new LikePredicate(columnReferenceLHS, not, pattern, escapeCharacter);
		assertEquals("foo LIKE '%middle%'", element.toString());
	}

	@Test
	public void testLikePredicateToSQLNot() throws ParseException {
		ColumnReference columnReferenceLHS = SqlElementUntils.createColumnReference("foo");
		Boolean not = Boolean.TRUE;
		Pattern pattern = SqlElementUntils.createPattern("'%middle%'");
		EscapeCharacter escapeCharacter = null;
		LikePredicate element = new LikePredicate(columnReferenceLHS, not, pattern, escapeCharacter);
		assertEquals("foo NOT LIKE '%middle%'", element.toString());
	}

	@Test
	public void testLikePredicateToSQLEscape() throws ParseException {
		ColumnReference columnReferenceLHS = SqlElementUntils.createColumnReference("foo");
		Boolean not = Boolean.TRUE;
		Pattern pattern = SqlElementUntils.createPattern("'%middle%'");
		EscapeCharacter escapeCharacter = SqlElementUntils.createEscapeCharacter("'$$'");
		LikePredicate element = new LikePredicate(columnReferenceLHS, not, pattern, escapeCharacter);
		assertEquals("foo NOT LIKE '%middle%' ESCAPE '$$'", element.toString());
	}

	@Test
	public void testHasPredicate() throws ParseException {
		Predicate predicate = new TableQueryParser("foo like '%aa%'").predicate();
		LikePredicate element = predicate.getFirstElementOfType(LikePredicate.class);
		assertEquals("foo", element.getLeftHandSide().toSql());
		List<UnsignedLiteral> values = Lists.newArrayList(element.getRightHandSideValues());
		assertNotNull(values);
		assertEquals(1, values.size());
		assertEquals("%aa%", values.get(0).toSqlWithoutQuotes());
	}

	@Test
	public void testHasPredicateEscape() throws ParseException {
		Predicate predicate = new TableQueryParser("foo like '%aa%' ESCAPE '@'").predicate();
		LikePredicate element = predicate.getFirstElementOfType(LikePredicate.class);
		assertEquals("foo", element.getLeftHandSide().toSql());
		List<UnsignedLiteral> values = Lists.newArrayList(element.getRightHandSideValues());
		assertNotNull(values);
		assertEquals(2, values.size());
		assertEquals("%aa%", values.get(0).toSqlWithoutQuotes());
		assertEquals("@", values.get(1).toSqlWithoutQuotes());
	}

	@Test
	public void testGetChildren() throws ParseException {
		Predicate predicate = new TableQueryParser("foo like '%aa%' ESCAPE '@'").predicate();
		LikePredicate element = predicate.getFirstElementOfType(LikePredicate.class);
		assertEquals(Arrays.asList(element.getColumnReferenceLHS(), element.getPattern(), element.getEscapeCharacter()),
				element.getChildren());
	}
}
