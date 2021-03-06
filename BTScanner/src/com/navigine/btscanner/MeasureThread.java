package com.navigine.btscanner;

import android.app.*;
import android.bluetooth.*;
import android.bluetooth.BluetoothAdapter.LeScanCallback;
import android.content.*;
import android.os.*;
import android.util.*;
import java.lang.*;
import java.util.*;

public class MeasureThread extends Thread
{
  public static final String TAG                  = "BTScanner";
  public static final int STORAGE_TIMEOUT         = 10000;  // in milliseconds
  public static final int BLUETOOTH_SCAN_TIMEOUT  = 1000;   // in milliseconds
  public static final int BLUETOOTH_SLEEP_TIMEOUT = 100;    // in milliseconds
  public static final int SLEEP_TIMEOUT           = 100;    // in milliseconds
  
  // Debug levels:
  //  1 - error messages
  //  2 - api functions
  //  3 - wifi, bluetooth, beacon updates
  public static int DEBUG_LEVEL = 2;
  
  private Context mContext  = null;
  private boolean mStopFlag = false;
  
  // Bluetooth parameters
  private boolean mBluetoothScan = false;
  private long mBluetoothTime = 0;
  private int mBluetoothScanTimeout = BLUETOOTH_SCAN_TIMEOUT;
  private int mBluetoothSleepTimeout = BLUETOOTH_SLEEP_TIMEOUT;
  private BluetoothAdapter mBluetoothAdapter = null;
  private BluetoothAdapter.LeScanCallback mLeScanCallBack = null;
  private boolean mBluetoothScanEnabled = false;
  
  public class ScanResult
  {
    public String   address   = "";
    public String   name      = "";
    public String   uuid      = "";
    public int      major     = 0;
    public int      minor     = 0;
    public int      rssi      = 0;
    public int      power     = 0;
    public int      battery   = 0;
    public float    distance  = 0.0f;
    public long     timeLabel = 0;
    
    ScanResult()
    { }
    
    ScanResult(ScanResult result)
    {
      address   = new String(result.address);
      name      = new String(result.name);
      uuid      = new String(result.uuid);
      major     = result.major;
      minor     = result.minor;
      rssi      = result.rssi;
      power     = result.power;
      battery   = result.battery;
      distance  = result.distance;
      timeLabel = result.timeLabel;
    }
  };
  
  private List<ScanResult> mScanResults = new ArrayList<ScanResult>();
  
  private static String byteArrayToHex(byte[] a)
  {
    StringBuilder sb = new StringBuilder(a.length * 2);
    for(byte b: a)
      sb.append(String.format("%02X", b & 0xff));
    return sb.toString();
  }
  
  private static String byteArrayToHex(byte[] a, int pos, int len)
  {
    StringBuilder sb = new StringBuilder(len * 2);
    for(int i = pos; i < pos + len && i < a.length; ++i)
      sb.append(String.format("%02X", (a[i] & 0xFF)));
    return sb.toString();
  }
  
  private static float calculateBeaconDistance(int rssi, int power)
  {
    return (float)Math.pow(10.0, (double)(power - rssi) / 20.0);
  }
  
  public MeasureThread(Context context)
  {
    mContext = context;
    super.start();

    synchronized (this)
    {
      // Setting up bluetooth adapter
      mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
      if (mBluetoothAdapter != null)
      {
        try
        {
          mLeScanCallBack = new BluetoothAdapter.LeScanCallback()
          {
            @Override public void onLeScan(final BluetoothDevice device, int rssi, byte[] scanRecord)
            {
              try
              {
                addBeacon(device.getAddress(), rssi, scanRecord);
              }
              catch (Throwable e)
              {
                if (DEBUG_LEVEL >= 1)
                  Log.e(TAG, Log.getStackTraceString(e));
              }
            }
          };
        }
        catch (Throwable e)
        {
          mLeScanCallBack = null;
          if (DEBUG_LEVEL >= 1)
            Log.e(TAG, Log.getStackTraceString(e));
        }
      }
    }

  }
  
  // Send request for thread to terminate
  public synchronized void terminate()
  {
    mStopFlag = true;
  }
  
  public synchronized boolean isBluetoothEnabled()
  {
    return mBluetoothAdapter != null &&
           mBluetoothAdapter.isEnabled();
  }
  
  public synchronized void setBluetoothEnabled(boolean enabled)
  {
    if (DEBUG_LEVEL >= 2)
      Log.d(TAG, String.format(Locale.ENGLISH, "Set bluetooth enabled: %s",
            (enabled ? "true" : "false")));
    try
    {
      if (mBluetoothAdapter == null)
        return;
      
      if (enabled)
      {
        if (mBluetoothAdapter.isEnabled())
          return;
        
        if (DEBUG_LEVEL >= 2)
          Log.d(TAG, "Enabling BLUETOOTH");
        mBluetoothAdapter.enable();
      }
      else
      {
        if (!mBluetoothAdapter.isEnabled())
          return;
        
        if (DEBUG_LEVEL >= 2)
         Log.d(TAG, "Disabling BLUETOOTH");
        mBluetoothAdapter.disable();
      }
    }
    catch (Throwable e)
    {
      if (DEBUG_LEVEL >= 1)
        Log.e(TAG, Log.getStackTraceString(e));
    }
  }
  
  public synchronized void setBluetoothScanEnabled(boolean enabled, int scanTimeout, int sleepTimeout)
  {
    if (DEBUG_LEVEL >= 2)
      Log.d(TAG, String.format(Locale.ENGLISH, "Set bluetooth scan enabled: %s, scan=%d ms, sleep=%d ms",
            (enabled ? "true" : "false"), scanTimeout, sleepTimeout));
    
    try
    {
      mBluetoothScanEnabled  = enabled;
      mBluetoothScanTimeout  = Math.max(scanTimeout,  BLUETOOTH_SCAN_TIMEOUT);
      mBluetoothSleepTimeout = Math.max(sleepTimeout, BLUETOOTH_SLEEP_TIMEOUT);
      
      // Setting up bluetooth scan
      if (mBluetoothAdapter != null &&
          mBluetoothAdapter.isEnabled() &&
          mLeScanCallBack != null)
      {
        if (DEBUG_LEVEL >= 3)
          Log.d(TAG, "Stop bluetooth scanning!");
        mBluetoothAdapter.stopLeScan(mLeScanCallBack);
        mBluetoothTime = System.currentTimeMillis();
        mBluetoothScan = false;
        
        if (mBluetoothScanEnabled)
        {
          if (DEBUG_LEVEL >= 3)
            Log.d(TAG, "Start bluetooth scanning!");
          mBluetoothAdapter.startLeScan(mLeScanCallBack);
          mBluetoothTime = System.currentTimeMillis();
          mBluetoothScan = true;
        }
      }
    }
    catch (Throwable e)
    {
      if (DEBUG_LEVEL >= 1)
        Log.e(TAG, Log.getStackTraceString(e));
    }
  }
  
  // Get the last scan results
  public synchronized List<ScanResult> getScanResults(long timeLabel)
  {
    List<ScanResult> L = new ArrayList<ScanResult>();
    for(int i = 0; i < mScanResults.size(); ++i)
    {
      ScanResult result = mScanResults.get(i);
      if (result.timeLabel >= timeLabel)
        L.add(new ScanResult(result));
    }
    return L;
  }

  private void addBeacon(String address, int rssi, byte[] data)
  {
    try
    {
      if (data == null || data.length == 0)
        return;
      
      String uuid = "";
      String name = "";
      int major   = 0;
      int minor   = 0;
      int power   = 0;
      int battery = 0;
      boolean ok  = false;
      
      for(int n = 0; n < data.length; )
      {
        int len = (data[n] & 0xFF);
        if (len == 0)
          break; // Packet has finished
        
        if (n + len >= data.length)
        {
          ok = false;
          break; // Incomplete packet
        }
        
        // Packet type
        int type = (data[n + 1] & 0xFF);
        switch (type)
        {
          case 0xFF: // Advertising packet
          {
            if (len != 26)
              break;
            
            int vendor = (data[n+2] & 0xFF) + (data[n+3] & 0xFF) * 256;
            if (vendor == 0x004C || vendor == 0x0059)
            {
              uuid += byteArrayToHex(data, n + 6, 4);
              uuid += "-";
              uuid += byteArrayToHex(data, n + 10, 2);
              uuid += "-";
              uuid += byteArrayToHex(data, n + 12, 2);
              uuid += "-";
              uuid += byteArrayToHex(data, n + 14, 2);
              uuid += "-";
              uuid += byteArrayToHex(data, n + 16, 6);
              major = (data[n + 22] & 0xFF) * 256 + (data[n + 23] & 0xFF);
              minor = (data[n + 24] & 0xFF) * 256 + (data[n + 25] & 0xFF);
              power = (data[n + 26] & 0xFF) - 256;
              ok = true;
            }
            break;
          }
          
          case 0x16: // Service packet
          {
            if (len < 10)
              break;
            
            int serviceUuid = (data[n+2] & 0xFF) * 256 + (data[n+3] & 0xFF);
            if (len == 10 && serviceUuid == 0x0DD0)
            {
              // Kontakt beacon service UUID
              name    = new String(data, n + 4, 4);
              battery = (data[n+10] & 0xFF);
              battery = Math.max(Math.min(battery, 100), 0);
            }
            else if (len == 12 && serviceUuid == 0xD0D0)
            {
              // iBecom service UUID
              battery = (data[n+11] & 0xFF) + (data[n+12] & 0xFF) * 256;
              battery = (battery - 319) * 100 / 140;
              battery = Math.max(Math.min(battery, 100), 0);
            }
            break;
          }
          
          default:
            break;
        }
        n += len + 1;
      }
      
      if (!ok)
        return;
      
      ScanResult result = new ScanResult();
      result.address    = address;
      result.name       = name;
      result.uuid       = uuid;
      result.major      = major;
      result.minor      = minor;
      result.rssi       = rssi;
      result.power      = power <= 0 && power >= -127 ? power : 0;
      result.battery    = battery;
      result.distance   = calculateBeaconDistance(rssi, power);
      result.timeLabel  = System.currentTimeMillis();
      
      if (DEBUG_LEVEL >= 3)
        Log.d(TAG, String.format(Locale.ENGLISH, "BEACON %s: uuid=%s; major=%05d; minor=%05d; rssi=%d; dist=%.2f",
              result.name, result.uuid, result.major, result.minor, result.rssi, result.distance));
      
      synchronized (this)
      {
        mScanResults.add(result);
      }
    }
    catch (Throwable e)
    {
      if (DEBUG_LEVEL >= 1)
        Log.e(TAG, Log.getStackTraceString(e));
    }
  }
  
  @Override public void run()
  {
    Looper.prepare();
    if (DEBUG_LEVEL >= 2)
      Log.d(TAG, "MeasureThread: started");
    
    while (!mStopFlag)
    {
      // Determine, if we must terminate
      long timeNow = System.currentTimeMillis();
      
      synchronized (this)
      {
        try
        {
          // Removing obsolete scan results
          int pos = 0;
          for(pos = 0; pos < mScanResults.size(); ++pos)
          {
            ScanResult result = mScanResults.get(pos);
            if (timeNow < result.timeLabel + STORAGE_TIMEOUT)
              break;
          }
          if (pos > 0)
            mScanResults.subList(0, pos).clear();
          
          // Restarting bluetooth scan (if necessary)
          if (isBluetoothEnabled() &&
              mBluetoothScanEnabled &&
              mLeScanCallBack != null)
          {
            if (mBluetoothScan && Math.abs(timeNow - mBluetoothTime) >= mBluetoothScanTimeout)
            {
              // Stop bluetooth scan
              if (DEBUG_LEVEL >= 3)
                Log.d(TAG, "Stop bluetooth scanning!");
              mBluetoothAdapter.stopLeScan(mLeScanCallBack);
              mBluetoothTime = timeNow;
              mBluetoothScan = false;
            }
            else if (!mBluetoothScan && Math.abs(timeNow - mBluetoothTime) >= mBluetoothSleepTimeout)
            {
              if (DEBUG_LEVEL >= 3)
                Log.d(TAG, "Start bluetooth scanning!");
              mBluetoothAdapter.startLeScan(mLeScanCallBack);
              mBluetoothTime = timeNow;
              mBluetoothScan = true;
            }
          }
        }
        catch (Throwable e)
        {
          if (DEBUG_LEVEL >= 1)
            Log.e(TAG, Log.getStackTraceString(e));
        }
      }
      
      // Sleeping some time
      try { Thread.sleep(SLEEP_TIMEOUT); } catch (Throwable e) { }
    } // end of loop
    
    synchronized (this)
    {
      try
      {
        if (mBluetoothAdapter != null && mLeScanCallBack != null)
          mBluetoothAdapter.stopLeScan(mLeScanCallBack);
      }
      catch (Throwable e)
      {
        if (DEBUG_LEVEL >= 1)
          Log.e(TAG, Log.getStackTraceString(e));
      }
    }
    
    Looper.myLooper().quit();
    if (DEBUG_LEVEL >= 2)
      Log.d(TAG, "MeasureThread: stopped");
  } // end of run()
}
