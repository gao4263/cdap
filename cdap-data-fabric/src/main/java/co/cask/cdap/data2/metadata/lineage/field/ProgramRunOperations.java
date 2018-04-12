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

import co.cask.cdap.api.lineage.field.Operation;
import co.cask.cdap.proto.id.ProgramRunId;

import java.util.Objects;
import java.util.Set;

/**
 * Represents the set of program runs along with the set of field operations
 * performed by each of the program run in that set.
 */
public class ProgramRunOperations {
  private final long checksum;
  private final Set<ProgramRunId> programRunIds;
  private final Set<Operation> operations;

  public ProgramRunOperations(long checksum, Set<ProgramRunId> programRunIds, Set<Operation> operations) {
    this.checksum = checksum;
    this.programRunIds = programRunIds;
    this.operations = operations;
  }

  public long getChecksum() {
    return checksum;
  }

  public Set<ProgramRunId> getProgramRunIds() {
    return programRunIds;
  }

  public Set<Operation> getOperations() {
    return operations;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ProgramRunOperations that = (ProgramRunOperations) o;
    return checksum == that.checksum;
  }

  @Override
  public int hashCode() {
    return Objects.hash(checksum);
  }
}
