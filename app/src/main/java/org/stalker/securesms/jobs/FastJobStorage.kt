package org.stalker.securesms.jobs

import org.stalker.securesms.database.JobDatabase
import org.stalker.securesms.jobmanager.Job
import org.stalker.securesms.jobmanager.persistence.ConstraintSpec
import org.stalker.securesms.jobmanager.persistence.DependencySpec
import org.stalker.securesms.jobmanager.persistence.FullSpec
import org.stalker.securesms.jobmanager.persistence.JobSpec
import org.stalker.securesms.jobmanager.persistence.JobStorage

class FastJobStorage(private val jobDatabase: JobDatabase) : JobStorage {

  private val jobs: MutableList<JobSpec> = mutableListOf()
  private val constraintsByJobId: MutableMap<String, MutableList<ConstraintSpec>> = mutableMapOf()
  private val dependenciesByJobId: MutableMap<String, MutableList<DependencySpec>> = mutableMapOf()

  @Synchronized
  override fun init() {
    jobs += jobDatabase.getAllJobSpecs()

    for (constraintSpec in jobDatabase.getAllConstraintSpecs()) {
      val jobConstraints: MutableList<ConstraintSpec> = constraintsByJobId.getOrPut(constraintSpec.jobSpecId) { mutableListOf() }
      jobConstraints += constraintSpec
    }

    for (dependencySpec in jobDatabase.getAllDependencySpecs().filterNot { it.hasCircularDependency() }) {
      val jobDependencies: MutableList<DependencySpec> = dependenciesByJobId.getOrPut(dependencySpec.jobId) { mutableListOf() }
      jobDependencies += dependencySpec
    }
  }

  @Synchronized
  override fun insertJobs(fullSpecs: List<FullSpec>) {
    val durable: List<FullSpec> = fullSpecs.filterNot { it.isMemoryOnly }

    if (durable.isNotEmpty()) {
      jobDatabase.insertJobs(durable)
    }

    for (fullSpec in fullSpecs) {
      jobs += fullSpec.jobSpec
      constraintsByJobId[fullSpec.jobSpec.id] = fullSpec.constraintSpecs.toMutableList()
      dependenciesByJobId[fullSpec.jobSpec.id] = fullSpec.dependencySpecs.toMutableList()
    }
  }

  @Synchronized
  override fun getJobSpec(id: String): JobSpec? {
    return jobs.firstOrNull { it.id == id }
  }

  @Synchronized
  override fun getAllJobSpecs(): List<JobSpec> {
    return ArrayList(jobs)
  }

  @Synchronized
  override fun getPendingJobsWithNoDependenciesInCreatedOrder(currentTime: Long): List<JobSpec> {
    val migrationJob: JobSpec? = getMigrationJob()

    return if (migrationJob != null && !migrationJob.isRunning && migrationJob.hasEligibleRunTime(currentTime)) {
      listOf(migrationJob)
    } else if (migrationJob != null) {
      emptyList()
    } else {
      jobs
        .groupBy {
          // Group together by queue. If it doesn't have a queue, we just use the ID, since it's unique and will give us all of the jobs without queues.
          it.queueKey ?: it.id
        }
        .map { byQueueKey: Map.Entry<String, List<JobSpec>> ->
          // We want to find the next job we should run within each queue. It should be the oldest job within the group of jobs with the highest priority.
          // We can get this by sorting by createTime, then taking first job in that list that has the max priority.
          byQueueKey.value
            .sortedBy { it.createTime }
            .maxByOrNull { it.priority }
        }
        .filterNotNull()
        .filter { job ->
          // Filter out all jobs with unmet dependencies
          dependenciesByJobId[job.id].isNullOrEmpty()
        }
        .filterNot { it.isRunning }
        .filter { job -> job.hasEligibleRunTime(currentTime) }
        .sortedBy { it.createTime }
        .sortedByDescending { it.priority }

      // Note: The priority sort at the end is safe because it's stable. That means that within jobs with the same priority, they will still be sorted by createTime.
    }
  }

  @Synchronized
  override fun getJobsInQueue(queue: String): List<JobSpec> {
    return jobs
      .filter { it.queueKey == queue }
      .sortedBy { it.createTime }
  }

  private fun getMigrationJob(): JobSpec? {
    return jobs
      .filter { it.queueKey == Job.Parameters.MIGRATION_QUEUE_KEY }
      .firstOrNull { firstInQueue(it) }
  }

  private fun firstInQueue(job: JobSpec): Boolean {
    return if (job.queueKey == null) {
      true
    } else {
      val firstInQueue: JobSpec? = jobs
        .filter { it.queueKey == job.queueKey }
        .minByOrNull { it.createTime }

      job == firstInQueue
    }
  }

  @Synchronized
  override fun getJobCountForFactory(factoryKey: String): Int {
    return jobs
      .filter { it.factoryKey == factoryKey }
      .size
  }

  @Synchronized
  override fun getJobCountForFactoryAndQueue(factoryKey: String, queueKey: String): Int {
    return jobs
      .filter { it.factoryKey == factoryKey && it.queueKey == queueKey }
      .size
  }

  @Synchronized
  override fun areQueuesEmpty(queueKeys: Set<String>): Boolean {
    return jobs.none { it.queueKey != null && queueKeys.contains(it.queueKey) }
  }

  @Synchronized
  override fun markJobAsRunning(id: String, currentTime: Long) {
    val job: JobSpec? = getJobById(id)
    if (job == null || !job.isMemoryOnly) {
      jobDatabase.markJobAsRunning(id, currentTime)
    }

    val iter = jobs.listIterator()

    while (iter.hasNext()) {
      val current: JobSpec = iter.next()
      if (current.id == id) {
        iter.set(
          current.copy(
            isRunning = true,
            lastRunAttemptTime = currentTime
          )
        )
      }
    }
  }

  @Synchronized
  override fun updateJobAfterRetry(id: String, currentTime: Long, runAttempt: Int, nextBackoffInterval: Long, serializedData: ByteArray?) {
    val job = getJobById(id)
    if (job == null || !job.isMemoryOnly) {
      jobDatabase.updateJobAfterRetry(id, currentTime, runAttempt, nextBackoffInterval, serializedData)
    }

    val iter = jobs.listIterator()
    while (iter.hasNext()) {
      val current = iter.next()
      if (current.id == id) {
        iter.set(
          current.copy(
            isRunning = false,
            runAttempt = runAttempt,
            lastRunAttemptTime = currentTime,
            nextBackoffInterval = nextBackoffInterval,
            serializedData = serializedData
          )
        )
      }
    }
  }

  @Synchronized
  override fun updateAllJobsToBePending() {
    jobDatabase.updateAllJobsToBePending()

    val iter = jobs.listIterator()
    while (iter.hasNext()) {
      val current = iter.next()
      iter.set(current.copy(isRunning = false))
    }
  }

  @Synchronized
  override fun updateJobs(jobSpecs: List<JobSpec>) {
    val durable: List<JobSpec> = jobSpecs
      .filter { updatedJob ->
        val found = getJobById(updatedJob.id)
        found != null && !found.isMemoryOnly
      }

    if (durable.isNotEmpty()) {
      jobDatabase.updateJobs(durable)
    }

    val updatesById: Map<String, JobSpec> = jobSpecs.associateBy { it.id }

    val iter = jobs.listIterator()
    while (iter.hasNext()) {
      val current = iter.next()
      val update = updatesById[current.id]

      if (update != null) {
        iter.set(update)
      }
    }
  }

  @Synchronized
  override fun deleteJob(jobId: String) {
    deleteJobs(listOf(jobId))
  }

  @Synchronized
  override fun deleteJobs(jobIds: List<String>) {
    val durableIds: List<String> = jobIds
      .mapNotNull { getJobById(it) }
      .filterNot { it.isMemoryOnly }
      .map { it.id }

    if (durableIds.isNotEmpty()) {
      jobDatabase.deleteJobs(durableIds)
    }

    val deleteIds: Set<String> = jobIds.toSet()
    jobs.removeIf { deleteIds.contains(it.id) }

    for (jobId in jobIds) {
      constraintsByJobId.remove(jobId)
      dependenciesByJobId.remove(jobId)

      for (dependencyList in dependenciesByJobId.values) {
        val iter = dependencyList.iterator()

        while (iter.hasNext()) {
          if (iter.next().dependsOnJobId == jobId) {
            iter.remove()
          }
        }
      }
    }
  }

  @Synchronized
  override fun getConstraintSpecs(jobId: String): List<ConstraintSpec> {
    return constraintsByJobId.getOrElse(jobId) { listOf() }
  }

  @Synchronized
  override fun getAllConstraintSpecs(): List<ConstraintSpec> {
    return constraintsByJobId.values.flatten()
  }

  @Synchronized
  override fun getDependencySpecsThatDependOnJob(jobSpecId: String): List<DependencySpec> {
    val all: MutableList<DependencySpec> = mutableListOf()

    var dependencyLayer: List<DependencySpec> = getSingleLayerOfDependencySpecsThatDependOnJob(jobSpecId)

    while (dependencyLayer.isNotEmpty()) {
      all += dependencyLayer

      dependencyLayer = dependencyLayer
        .map { getSingleLayerOfDependencySpecsThatDependOnJob(it.jobId) }
        .flatten()
    }

    return all
  }

  private fun getSingleLayerOfDependencySpecsThatDependOnJob(jobSpecId: String): List<DependencySpec> {
    return dependenciesByJobId
      .values
      .flatten()
      .filter { it.dependsOnJobId == jobSpecId }
  }

  override fun getAllDependencySpecs(): List<DependencySpec> {
    return dependenciesByJobId.values.flatten()
  }

  private fun getJobById(id: String): JobSpec? {
    return jobs.firstOrNull { it.id == id }
  }

  /**
   * Note that this is currently only checking a specific kind of circular dependency -- ones that are
   * created between dependencies and queues.
   *
   * More specifically, dependencies where one job depends on another job in the same queue that was
   * scheduled *after* it. These dependencies will never resolve. Under normal circumstances these
   * won't occur, but *could* occur if the user changed their clock (either purposefully or automatically).
   *
   * Rather than go through and delete them from the database, removing them from memory at load time
   * serves the same effect and doesn't require new write methods. This should also be very rare.
   */
  private fun DependencySpec.hasCircularDependency(): Boolean {
    val job = getJobById(this.jobId)
    val dependsOnJob = getJobById(this.dependsOnJobId)

    if (job == null || dependsOnJob == null) {
      return false
    }

    if (job.queueKey == null || dependsOnJob.queueKey == null) {
      return false
    }

    if (job.queueKey != dependsOnJob.queueKey) {
      return false
    }

    return dependsOnJob.createTime > job.createTime
  }

  /**
   * Whether or not the job's eligible to be run based off of it's [Job.nextBackoffInterval] and other properties.
   */
  private fun JobSpec.hasEligibleRunTime(currentTime: Long): Boolean {
    return this.lastRunAttemptTime > currentTime || (this.lastRunAttemptTime + this.nextBackoffInterval) < currentTime
  }
}
