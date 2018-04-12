/*
 * Copyright © 2018 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.data2.metadata.lineage.field;

import co.cask.cdap.api.lineage.field.EndPoint;

import java.util.Set;

/**
 * Interface for reading the {@link FieldLineageDataset} store.
 */
public interface FieldLineageReader {
  /**
   * Get set of fields generated for the specified EndPoint in a given time range.
   *
   * @param endPoint the EndPoint for which the fields need to be returned
   * @param start start time in milliseconds
   * @param end end time in milliseconds
   * @return set of fields generated for a given EndPoint
   */
  Set<String> getFields(EndPoint endPoint, long start, long end);

  /**
   * Get the incoming summary for the specified EndPointField in a given time range.
   * Incoming summary consists of set of EndPointFields which were responsible for generating
   * the specified EndPointField.
   *
   * @param endPointField the EndPointField for which incoming summary to be returned
   * @param start start time in milliseconds
   * @param end end time in milliseconds
   * @return the set of EndPointFields
   */
  Set<EndPointField> getIncomingSummary(EndPointField endPointField, long start, long end);

  /**
   * Get the outgoing summary for the specified EndPointField in a given time range.
   * Outgoing summary consists of set of EndPointFields which were generated by the
   * specified EndPointField.
   *
   * @param endPointField the EndPointField for which outgoing summary to be returned
   * @param start start time in milliseconds
   * @param end end time in milliseconds
   * @return the set of EndPointFields
   */
  Set<EndPointField> getOutgoingSummary(EndPointField endPointField, long start, long end);

  /**
   * Get the set of operations which were responsible for generating the fields
   * of the specified EndPoint in a given time range. Along with the operations, program
   * runs are also returned which performed these operations.
   *
   * @param endPoint the EndPoint for which incoming operations are to be returned
   * @param start start time in milliseconds
   * @param end end time in milliseconds
   * @return the operations and program run information
   */
  Set<ProgramRunOperations> getIncomingOperations(EndPoint endPoint, long start, long end);

  /**
   * Get the set of operations which were performed on the fields of the specified EndPoint
   * to generate fields of the downstream EndPoints. Along with the operations, program
   * runs are also returned which performed these operations.
   *
   * @param endPoint the EndPoint for which outgoing operations are to be returned
   * @param start start time in milliseconds
   * @param end end time in milliseconds
   * @return the operations and program run information
   */
  Set<ProgramRunOperations> getOutgoingOperations(EndPoint endPoint, long start, long end);
}
