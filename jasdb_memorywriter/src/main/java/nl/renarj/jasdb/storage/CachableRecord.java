/*
 * The JASDB software and code is Copyright protected 2011 and owned by Renze de Vries
 * 
 * All the code and design principals in the codebase are also Copyright 2011 
 * protected and owned Renze de Vries. Any unauthorized usage of the code or the 
 * design and principals as in this code is prohibited.
 */
package nl.renarj.jasdb.storage;

import nl.renarj.core.caching.CachableItem;
import nl.renarj.jasdb.core.storage.RecordResult;

public class CachableRecord implements CachableItem {
	private RecordResult result;
	
	public CachableRecord(RecordResult result) {
		this.result = result;
	}
	
	public RecordResult getResult() {
		return this.result;
	}
	
	@Override
	public long getObjectSize() {
        return result.getRecordSize();
	}

}
