#!/usr/bin/env ruby

require 'pg'
require 'logger'

LOG = Logger.new(STDOUT)
LOG.level = Logger::INFO

LOG.info 'Version of libpg: ' + PG.library_version.to_s

class DbChecker
  def initialize
    @con = nil
    @instanceId = ENV.fetch('INSTANCE_ID', 'unknown')
    LOG.info "INSTANCE_ID: #{@instanceId}"
  end

  def connectDb()
    LOG.info "connecting to server"
    @con = PG.connect :dbname => ENV.fetch('DB_NAME', 'postgres'),
      :user => ENV.fetch('DB_USERNAME', 'postgres'),
      :password => ENV.fetch('DB_PASSWORD', 'postgres'),
      :host => ENV.fetch('DB_HOST', 'localhost'),
      :port => ENV.fetch('DB_PORT', 5432)

    LOG.info "Server version: #{@con.server_version}"
    return @con
  end

  def initDb()
    @con.exec "CREATE TABLE IF NOT EXISTS Counting(Id VARCHAR(255) PRIMARY KEY, 
                    Number INT)"
    rs = @con.exec_params "SELECT * FROM Counting WHERE Id = $1", [@instanceId]
    if rs.ntuples == 0
      @con.exec_params "INSERT INTO Counting VALUES ($1, 0)", [@instanceId]
    end
  end

  def checkDbValues(number)
    rs = @con.exec_params "SELECT * FROM Counting WHERE Id= $1", [@instanceId]
    LOG.info "#{@instanceId} current number: #{rs[0]['number']}"
    if rs[0]['number'].to_i != number
      LOG.error "expected number to be #{number} but got #{rs[0]['number']}"
      return false
    end
    return true
  end

  def updateDbValues(number)
    @con.transaction do |c|
      c.exec_params "UPDATE Counting SET Number= $1 WHERE Id = $2", [number, @instanceId]
    end
  end
end



begin
  updateInterval = ENV.fetch('UPDATE_INTERVAL', "0.1").to_i
  LOG.info "update interval: #{updateInterval}"
  c = DbChecker.new
  con = c.connectDb

  c.initDb

  number = 1
  while true do
    begin
      c.checkDbValues(number - 1)

      c.updateDbValues number

      c.checkDbValues number

      $stdout.flush
      sleep updateInterval

      number+=1
    rescue PG::Error => e
      LOG.error "#{e.message}"
      LOG.info "reconnecting..."
      $stdout.flush
      sleep 2
      con.close if con
      10.times do |retrycount|
        begin
          sleep 3
          con = c.connectDb
          $stdout.flush
          break
        rescue PG::Error => e
          LOG.warn e.message
          $stdout.flush
        end
      end
      sleep 0.5
    end
  end


rescue PG::Error => e
  LOG.error e.message
ensure
  con.close if con
end
