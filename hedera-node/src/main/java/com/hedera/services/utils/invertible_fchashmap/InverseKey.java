/*
 * (c) 2016-2021 Swirlds, Inc.
 *
 * This software is the confidential and proprietary information of
 * Swirlds, Inc. ("Confidential Information"). You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with Swirlds.
 *
 * SWIRLDS MAKES NO REPRESENTATIONS OR WARRANTIES ABOUT THE SUITABILITY OF
 * THE SOFTWARE, EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
 * TO THE IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE, OR NON-INFRINGEMENT. SWIRLDS SHALL NOT BE LIABLE FOR
 * ANY DAMAGES SUFFERED BY LICENSEE AS A RESULT OF USING, MODIFYING OR
 * DISTRIBUTING THIS SOFTWARE OR ITS DERIVATIVES.
 */

package com.hedera.services.utils.invertible_fchashmap;


import java.util.Objects;

/**
 * In an {@link FCInvertibleHashMap FCInvertibleHashMap},
 * an InverseKey uniquely identifies a particular key.
 *
 * @param <I>
 */
public class InverseKey<I> {

	private final I valueId;
	private final int index;

	/**
	 * Create a new inverse key.
	 *
	 * @param valueId
	 * 		the ID of the value that this key references
	 * @param index
	 * 		the index of this key
	 */
	public InverseKey(final I valueId, final int index) {
		this.valueId = valueId;
		this.index = index;
	}

	/**
	 * Get the value ID referenced by a key.
	 */
	public I getValueId() {
		return valueId;
	}

	/**
	 * Get the index of a key.
	 */
	public int getIndex() {
		return index;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean equals(final Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		final InverseKey<?> that = (InverseKey<?>) o;
		return index == that.index && Objects.equals(valueId, that.valueId);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public int hashCode() {
		return Objects.hash(valueId, index);
	}
}
