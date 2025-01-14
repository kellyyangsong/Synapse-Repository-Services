package org.sagebionetworks.table.cluster.view.filter;


import java.util.Objects;
import java.util.Set;

import org.sagebionetworks.repo.model.table.ReplicationType;
import org.sagebionetworks.repo.model.table.SubType;
import org.sagebionetworks.util.ValidateArgument;

/**
 * ViewFilter for a scope defined by a hierarchy of parentIds
 *
 */
public class HierarchicaFilter extends AbstractViewFilter {

	
	protected final Set<Long> parentIds;
	
	public HierarchicaFilter(ReplicationType mainType, Set<SubType> subTypes, Set<Long> scope) {
		this(mainType, subTypes, null, null, scope);
	}

	public HierarchicaFilter(ReplicationType mainType, Set<SubType> subTypes, Set<Long> limitObjectIds, Set<String> excludeKeys,
			Set<Long> scope) {
		super(mainType, subTypes, limitObjectIds, excludeKeys);
		ValidateArgument.required(scope, "scope");
		this.parentIds = scope;
		this.params.put("parentIds", scope);
	}

	@Override
	public boolean isEmpty() {
		return this.parentIds.isEmpty();
	}

	@Override
	public String getFilterSql() {
		return super.getFilterSql()+ " AND R.PARENT_ID IN (:parentIds) AND R.OBJECT_VERSION = R.CURRENT_VERSION";
	}
	
	@Override
	public String getObjectIdFilterSql() {
		// this filter includes all versions of each object.
		return super.getFilterSql()+ " AND R.PARENT_ID IN (:parentIds)";
	}
	
	public Set<Long> getParentIds() {
		return parentIds;
	}

	@Override
	public Builder newBuilder() {
		return new Builder(mainType, subTypes, limitObjectIds, excludeKeys, parentIds);
	}
	
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = super.hashCode();
		result = prime * result + Objects.hash(parentIds);
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (!super.equals(obj)) {
			return false;
		}
		if (!(obj instanceof HierarchicaFilter)) {
			return false;
		}
		HierarchicaFilter other = (HierarchicaFilter) obj;
		return Objects.equals(parentIds, other.parentIds);
	}


	@Override
	public String toString() {
		return "HierarchyFilter [scope=" + parentIds + ", mainType=" + mainType + ", subTypes=" + subTypes
				+ ", limitObjectIds=" + limitObjectIds + ", excludeKeys=" + excludeKeys + ", params=" + params + "]";
	}


	public static class Builder extends AbstractBuilder {
		
		 Set<Long> scope;

		public Builder(ReplicationType mainType, Set<SubType> subTypes, Set<Long> limitObjectIds,
				Set<String> excludeKeys,  Set<Long> scope) {
			super(mainType, subTypes, limitObjectIds, excludeKeys);
			this.scope = scope;
		}

		@Override
		public ViewFilter build() {
			return new HierarchicaFilter(mainType, subTypes, limitObjectIds, excludeKeys, scope);
		}
		
	}
	
}
